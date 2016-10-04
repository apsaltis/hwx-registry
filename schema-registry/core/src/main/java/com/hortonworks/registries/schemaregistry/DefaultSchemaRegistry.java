/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hortonworks.registries.schemaregistry;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.hortonworks.registries.common.QueryParam;
import com.hortonworks.registries.common.util.FileStorage;
import com.hortonworks.registries.schemaregistry.serde.SerDesException;
import com.hortonworks.registries.storage.Storable;
import com.hortonworks.registries.storage.StorageManager;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default implementation for schema registry.
 * <p>
 * Remove todos with respective JIRAs created
 */
public class DefaultSchemaRegistry implements ISchemaRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultSchemaRegistry.class);

    private final StorageManager storageManager;
    private final FileStorage fileStorage;
    private final Collection<? extends SchemaProvider> schemaProviders;
    private final Map<String, SchemaProvider> schemaTypeWithProviders = new HashMap<>();
    private final Object addOrUpdateLock = new Object();
    private Options options;
    private SchemaVersionInfoCache schemaVersionInfoCache;

    public DefaultSchemaRegistry(StorageManager storageManager, FileStorage fileStorage, Collection<? extends SchemaProvider> schemaProviders) {
        this.storageManager = storageManager;
        this.fileStorage = fileStorage;
        this.schemaProviders = schemaProviders;
    }

    @Override
    public void init(Map<String, Object> props) {
        options = new Options(props);
        for (SchemaProvider schemaProvider : schemaProviders) {
            schemaTypeWithProviders.put(schemaProvider.getType(), schemaProvider);
        }
        schemaVersionInfoCache = new SchemaVersionInfoCache(key -> retrieveSchemaVersionInfo(key), options.getMaxSchemaCacheSize(), options.getSchemaExpiryInSecs());
    }

    @Override
    public Long addSchemaMetadata(SchemaMetadata schemaMetadata) {
        SchemaMetadataStorable givenSchemaMetadataStorable = new SchemaMetadataInfo(schemaMetadata).toSchemaInfoStorable();

        Long id;
        synchronized (addOrUpdateLock) {
            Storable schemaMetadataStorable = storageManager.get(givenSchemaMetadataStorable.getStorableKey());
            if (schemaMetadataStorable != null) {
                id = schemaMetadataStorable.getId();
            } else {
                final Long nextId = storageManager.nextId(givenSchemaMetadataStorable.getNameSpace());
                givenSchemaMetadataStorable.setId(nextId);
                givenSchemaMetadataStorable.setTimestamp(System.currentTimeMillis());
                storageManager.add(givenSchemaMetadataStorable);
                id = givenSchemaMetadataStorable.getId();
            }
        }

        return id;
    }

    public Integer addSchemaVersion(SchemaMetadata schemaMetadata, String schemaText, String description) throws IncompatibleSchemaException, InvalidSchemaException {
        Integer version;
        // todo handle with minimal lock usage.
        synchronized (addOrUpdateLock) {
            // check whether there exists schema-metadata for schema-metadata-key
            String schemaName = schemaMetadata.getName();
            SchemaMetadataInfo retrievedschemaMetadataInfo = getSchemaMetadata(schemaName);
            if (retrievedschemaMetadataInfo != null) {
                // check whether the same schema text exists
                try {
                    version = getSchemaVersion(schemaName, schemaText);
                } catch (SchemaNotFoundException e) {
                    version = createSchemaVersion(schemaMetadata, retrievedschemaMetadataInfo.getId(), schemaText, description);
                }
            } else {
                Long schemaMetadataId = addSchemaMetadata(schemaMetadata);
                version = createSchemaVersion(schemaMetadata, schemaMetadataId, schemaText, description);
            }
        }

        return version;
    }

    public Integer addSchemaVersion(String schemaName, String schemaText, String description) throws SchemaNotFoundException, IncompatibleSchemaException, InvalidSchemaException {
        Integer version;
        // todo handle with minimal lock usage.
        synchronized (addOrUpdateLock) {
            // check whether there exists schema-metadata for schema-metadata-key
            SchemaMetadataInfo schemaMetadataInfo = getSchemaMetadata(schemaName);
            if (schemaMetadataInfo != null) {
                SchemaMetadata schemaMetadata = schemaMetadataInfo.getSchemaMetadata();
                // check whether the same schema text exists
                version = findSchemaVersion(schemaMetadata.getType(), schemaText, schemaMetadataInfo.getId());
                if (version == null) {
                    version = createSchemaVersion(schemaMetadata, schemaMetadataInfo.getId(), schemaText, description);
                }
            } else {
                throw new SchemaNotFoundException("Schema not found with the given schemaName: " + schemaName);
            }
        }

        return version;
    }

    private Integer createSchemaVersion(SchemaMetadata schemaMetadata, Long schemaMetadataId, String schemaText, String description) throws IncompatibleSchemaException, InvalidSchemaException {

        Preconditions.checkNotNull(schemaMetadataId, "schemaMetadataId must not be null");

        String type = schemaMetadata.getType();

        // generate fingerprint, it parses the schema and checks for semantic validation.
        // throws InvalidSchemaException for invalid schemas.
        final String fingerprint = getFingerprint(type, schemaText);
        final String schemaName = schemaMetadata.getName();

        SchemaVersionStorable schemaVersionStorable = new SchemaVersionStorable();
        final Long schemaInstanceId = storageManager.nextId(schemaVersionStorable.getNameSpace());
        schemaVersionStorable.setId(schemaInstanceId);
        schemaVersionStorable.setSchemaMetadataId(schemaMetadataId);

        schemaVersionStorable.setFingerprint(fingerprint);

        schemaVersionStorable.setName(schemaName);

        schemaVersionStorable.setSchemaText(schemaText);
        schemaVersionStorable.setDescription(description);
        schemaVersionStorable.setTimestamp(System.currentTimeMillis());

        //todo fix this by generating version sequence for each schema in storage layer or explore other ways to make it scalable
        synchronized (addOrUpdateLock) {

            Collection<SchemaVersionInfo> schemaVersionInfos = findAllVersions(schemaName);
            Integer version = 0;
            if (schemaVersionInfos != null && !schemaVersionInfos.isEmpty()) {
                SchemaVersionInfo latestSchemaVersionInfo = null;
                for (SchemaVersionInfo schemaVersionInfo : schemaVersionInfos) {
                    Integer curVersion = schemaVersionInfo.getVersion();
                    if (curVersion >= version) {
                        latestSchemaVersionInfo = schemaVersionInfo;
                        version = curVersion;
                    }
                }

                version = latestSchemaVersionInfo.getVersion();
                // check for compatibility
                if (!schemaTypeWithProviders.get(type).isCompatible(schemaText, latestSchemaVersionInfo.getSchemaText(), schemaMetadata.getCompatibility())) {
                    throw new IncompatibleSchemaException("Given schema is not compatible with earlier schema versions");
                }
            }
            schemaVersionStorable.setVersion(version + 1);

            storageManager.add(schemaVersionStorable);

            String storableNamespace = new SchemaFieldInfoStorable().getNameSpace();
            List<SchemaFieldInfo> schemaFieldInfos = schemaTypeWithProviders.get(type).generateFields(schemaVersionStorable.getSchemaText());
            for (SchemaFieldInfo schemaFieldInfo : schemaFieldInfos) {
                final Long fieldInstanceId = storageManager.nextId(storableNamespace);
                SchemaFieldInfoStorable schemaFieldInfoStorable = schemaFieldInfo.toFieldInfoStorable(fieldInstanceId);
                schemaFieldInfoStorable.setSchemaInstanceId(schemaInstanceId);
                schemaFieldInfoStorable.setTimestamp(System.currentTimeMillis());
                storageManager.add(schemaFieldInfoStorable);
            }
        }

        return schemaVersionStorable.getVersion();
    }

    @Override
    public SchemaMetadataInfo getSchemaMetadata(String schemaName) {
        SchemaMetadataStorable givenSchemaMetadataStorable = new SchemaMetadataStorable();
        givenSchemaMetadataStorable.setName(schemaName);

        SchemaMetadataStorable schemaMetadataStorable1 = storageManager.get(givenSchemaMetadataStorable.getStorableKey());
        return schemaMetadataStorable1 != null ? SchemaMetadataInfo.fromSchemaInfoStorable(schemaMetadataStorable1) : null;
    }

    @Override
    public Collection<SchemaMetadata> findSchemaMetadata(Map<String, String> filters) {
        // todo get only few selected columns instead of getting the whole row.
        Collection<SchemaMetadataStorable> storables;

        if (filters == null || filters.isEmpty()) {
            storables = storageManager.list(SchemaMetadataStorable.NAME_SPACE);
        } else {
            List<QueryParam> queryParams =
                    filters.entrySet()
                            .stream()
                            .map(entry -> new QueryParam(entry.getKey(), entry.getValue()))
                            .collect(Collectors.toList());
            storables = storageManager.find(SchemaVersionStorable.NAME_SPACE, queryParams);
        }

        return storables != null && !storables.isEmpty()
                ? storables.stream().map(schemaMetadataStorable ->
                new SchemaMetadata.Builder(schemaMetadataStorable.getName())
                        .type(schemaMetadataStorable.getType())
                        .schemaGroup(schemaMetadataStorable.getSchemaGroup())
                        .build()
        )
                .collect(Collectors.toList())
                : Collections.emptyList();
    }

    @Override
    public Collection<SchemaVersionKey> findSchemasWithFields(SchemaFieldQuery schemaFieldQuery) {
        List<QueryParam> queryParams = buildQueryParam(schemaFieldQuery);

        Collection<SchemaFieldInfoStorable> fieldInfos = storageManager.find(SchemaFieldInfoStorable.STORABLE_NAME_SPACE, queryParams);
        Collection<SchemaVersionKey> schemaVersionKeys;
        if (fieldInfos != null && !fieldInfos.isEmpty()) {
            List<Long> schemaIds = fieldInfos.stream()
                    .map(schemaFieldInfoStorable -> schemaFieldInfoStorable.getSchemaInstanceId())
                    .collect(Collectors.toList());

            // todo get only few selected columns instead of getting the whole row.
            // add OR query to find items from store
            schemaVersionKeys = new ArrayList<>();
            for (Long schemaId : schemaIds) {
                SchemaVersionKey schemaVersionKey = getSchemaKey(schemaId);
                if (schemaVersionKey != null) {
                    schemaVersionKeys.add(schemaVersionKey);
                }
            }
        } else {
            schemaVersionKeys = Collections.emptyList();
        }

        return schemaVersionKeys;
    }

    private SchemaVersionKey getSchemaKey(Long schemaId) {
        SchemaVersionKey schemaVersionKey = null;

        List<QueryParam> queryParams = Collections.singletonList(new QueryParam(SchemaVersionStorable.ID, schemaId.toString()));
        Collection<SchemaVersionStorable> versionedSchemas = storageManager.find(SchemaVersionStorable.NAME_SPACE, queryParams);
        if (versionedSchemas != null && !versionedSchemas.isEmpty()) {
            SchemaVersionStorable storable = versionedSchemas.iterator().next();
            schemaVersionKey = new SchemaVersionKey(storable.getName(), storable.getVersion());
        }

        return schemaVersionKey;
    }

    private List<QueryParam> buildQueryParam(SchemaFieldQuery schemaFieldQuery) {
        List<QueryParam> queryParams = new ArrayList<>(3);
        if (schemaFieldQuery.getNamespace() != null) {
            queryParams.add(new QueryParam(SchemaFieldInfoStorable.FIELD_NAMESPACE, schemaFieldQuery.getNamespace()));
        }
        if (schemaFieldQuery.getName() != null) {
            queryParams.add(new QueryParam(SchemaFieldInfoStorable.NAME, schemaFieldQuery.getName()));
        }
        if (schemaFieldQuery.getType() != null) {
            queryParams.add(new QueryParam(SchemaFieldInfoStorable.TYPE, schemaFieldQuery.getType()));
        }

        return queryParams;
    }

    @Override
    public Collection<SchemaVersionInfo> findAllVersions(final String schemaName) {
        List<QueryParam> queryParams = Collections.singletonList(new QueryParam(SchemaVersionStorable.NAME, schemaName));

        Collection<SchemaVersionStorable> storables = storageManager.find(SchemaVersionStorable.NAME_SPACE, queryParams);

        return (storables != null && !storables.isEmpty())
                ? storables.stream().map(schemaVersionStorable -> new SchemaVersionInfo(schemaVersionStorable)).collect(Collectors.toList())
                : Collections.emptyList();
    }

    @Override
    public Integer getSchemaVersion(String schemaName, String schemaText) throws SchemaNotFoundException, InvalidSchemaException {
        SchemaMetadataInfo schemaMetadataInfo = getSchemaMetadata(schemaName);
        if (schemaMetadataInfo == null) {
            throw new SchemaNotFoundException("No schema found for schema metadata key: " + schemaName);
        }

        Long schemaMetadataId = schemaMetadataInfo.getId();
        Integer result = findSchemaVersion(schemaMetadataInfo.getSchemaMetadata().getType(), schemaText, schemaMetadataId);

        if (result == null) {
            throw new SchemaNotFoundException("No schema found for schema metadata key: " + schemaName);
        }

        return result;
    }

    private Integer findSchemaVersion(String type, String schemaText, Long schemaMetadataId) throws InvalidSchemaException {
        String fingerPrint = getFingerprint(type, schemaText);
        LOG.debug("Fingerprint of the given schema [{}] is [{}]", schemaText, fingerPrint);
        List<QueryParam> queryParams = Lists.newArrayList(
                new QueryParam(SchemaVersionStorable.SCHEMA_METADATA_ID, schemaMetadataId.toString()),
                new QueryParam(SchemaVersionStorable.FINGERPRINT, fingerPrint));

        Collection<SchemaVersionStorable> versionedSchemas = storageManager.find(SchemaVersionStorable.NAME_SPACE, queryParams);

        Integer result = null;
        if (versionedSchemas != null && !versionedSchemas.isEmpty()) {
            if (versionedSchemas.size() > 1) {
                LOG.warn("Exists more than one schema with schemaMetadataId: [{}] and schemaText [{}]", schemaMetadataId, schemaText);
            }

            SchemaVersionStorable schemaVersionStorable = versionedSchemas.iterator().next();
            result = schemaVersionStorable.getVersion();
        }

        return result;
    }

    private String getFingerprint(String type, String schemaText) throws InvalidSchemaException {
        return Hex.encodeHexString(schemaTypeWithProviders.get(type).getFingerprint(schemaText));
    }

    @Override
    public SchemaVersionInfo getSchemaVersionInfo(SchemaVersionKey schemaVersionKey) throws SchemaNotFoundException {
        return schemaVersionInfoCache.getSchema(schemaVersionKey);
    }

    SchemaVersionInfo retrieveSchemaVersionInfo(SchemaVersionKey schemaVersionKey) throws SchemaNotFoundException {
        String schemaName = schemaVersionKey.getSchemaName();
        SchemaMetadataInfo schemaMetadataInfo = getSchemaMetadata(schemaName);

        if (schemaMetadataInfo != null) {
            Integer version = schemaVersionKey.getVersion();
            Long schemaMetadataId = schemaMetadataInfo.getId();
            List<QueryParam> queryParams = Lists.newArrayList(
                    new QueryParam(SchemaVersionStorable.SCHEMA_METADATA_ID, schemaMetadataId.toString()),
                    new QueryParam(SchemaVersionStorable.VERSION, version.toString()));

            Collection<SchemaVersionStorable> versionedSchemas = storageManager.find(SchemaVersionStorable.NAME_SPACE, queryParams);
            if (versionedSchemas != null && !versionedSchemas.isEmpty()) {
                if (versionedSchemas.size() > 1) {
                    LOG.warn("More than one schema exists with metadataId: [{}] and version [{}]", schemaMetadataId, version);
                }
                return new SchemaVersionInfo(versionedSchemas.iterator().next());
            } else {
                throw new SchemaNotFoundException("No Schema version exists with schemaMetadataId " + schemaMetadataId + " and version " + version);
            }
        }

        throw new SchemaNotFoundException("No SchemaMetadata exists with key: " + schemaName);
    }

    private SchemaMetadataStorable getSchemaMetadataStorable(List<QueryParam> queryParams) {
        Collection<SchemaMetadataStorable> schemaMetadataStorables = storageManager.find(SchemaMetadataStorable.NAME_SPACE, queryParams);
        SchemaMetadataStorable schemaMetadataStorable = null;
        if (schemaMetadataStorables != null && !schemaMetadataStorables.isEmpty()) {
            if (schemaMetadataStorables.size() > 1) {
                LOG.warn("Received more than one schema with query parameters [{}]", queryParams);
            }
            schemaMetadataStorable = schemaMetadataStorables.iterator().next();
            LOG.debug("Schema found in registry with query parameters [{}]", queryParams);
        } else {
            LOG.debug("No schemas found in registry with query parameters [{}]", queryParams);
        }

        return schemaMetadataStorable;
    }

    @Override
    public SchemaVersionInfo getLatestSchemaVersionInfo(String schemaName) throws SchemaNotFoundException {
        Collection<SchemaVersionInfo> schemaVersionInfos = findAllVersions(schemaName);

        SchemaVersionInfo latestSchema = null;
        if (schemaVersionInfos != null && !schemaVersionInfos.isEmpty()) {
            Integer curVersion = Integer.MIN_VALUE;
            for (SchemaVersionInfo schemaVersionInfo : schemaVersionInfos) {
                if (schemaVersionInfo.getVersion() > curVersion) {
                    latestSchema = schemaVersionInfo;
                    curVersion = schemaVersionInfo.getVersion();
                }
            }
        }

        return latestSchema;
    }

    public boolean isCompatible(String schemaName, String toSchema) throws SchemaNotFoundException {
        Collection<SchemaVersionInfo> existingSchemaVersionInfoStorable = findAllVersions(schemaName);
        Collection<String> schemaTexts =
                existingSchemaVersionInfoStorable.stream()
                        .map(schemaInfoStorable -> schemaInfoStorable.getSchemaText()).collect(Collectors.toList());

        SchemaMetadataInfo schemaMetadataInfo = getSchemaMetadata(schemaName);

        SchemaMetadata schemaMetadata = schemaMetadataInfo.getSchemaMetadata();
        return isCompatible(schemaMetadata.getType(), toSchema, schemaTexts, schemaMetadata.getCompatibility());
    }

    public boolean isCompatible(SchemaVersionKey schemaVersionKey,
                                String toSchema) throws SchemaNotFoundException {
        String schemaName = schemaVersionKey.getSchemaName();

        SchemaVersionInfo existingSchemaVersionInfo = getSchemaVersionInfo(schemaVersionKey);
        String schemaText = existingSchemaVersionInfo.getSchemaText();
        SchemaMetadataInfo schemaMetadataInfo = getSchemaMetadata(schemaName);
        SchemaMetadata schemaMetadata = schemaMetadataInfo.getSchemaMetadata();
        return isCompatible(schemaMetadata.getType(), toSchema, Collections.singletonList(schemaText), schemaMetadata.getCompatibility());
    }

    private boolean isCompatible(String type,
                                 String toSchema,
                                 Collection<String> existingSchemas,
                                 SchemaProvider.Compatibility compatibility) {
        SchemaProvider schemaProvider = schemaTypeWithProviders.get(type);
        if (schemaProvider == null) {
            throw new IllegalStateException("No SchemaProvider registered for type: " + type);
        }

        return schemaProvider.isCompatible(toSchema, existingSchemas, compatibility);
    }

    @Override
    public String uploadFile(InputStream inputStream) {
        String fileName = UUID.randomUUID().toString();
        try {
            String uploadedFilePath = fileStorage.uploadFile(inputStream, fileName);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return fileName;
    }

    @Override
    public InputStream downloadFile(String fileId) throws IOException {
        return fileStorage.downloadFile(fileId);
    }

    @Override
    public Long addSerDesInfo(SerDesInfo serDesInfo) {
        SerDesInfoStorable serDesInfoStorable = SerDesInfoStorable.fromSerDesInfo(serDesInfo);
        Long nextId = storageManager.nextId(serDesInfoStorable.getNameSpace());
        serDesInfoStorable.setId(nextId);
        serDesInfoStorable.setTimestamp(System.currentTimeMillis());
        storageManager.add(serDesInfoStorable);

        return nextId;
    }

    @Override
    public SerDesInfo getSerDesInfo(Long serDesId) {
        SerDesInfoStorable serDesInfoStorable = new SerDesInfoStorable();
        serDesInfoStorable.setId(serDesId);
        return ((SerDesInfoStorable) storageManager.get(serDesInfoStorable.getStorableKey())).toSerDesInfo();
    }

    @Override
    public Collection<SerDesInfo> getSchemaSerializers(Long schemaMetadataId) {
        return getSerDesInfos(schemaMetadataId, true);
    }

    private Collection<SchemaSerDesMapping> getSchemaSerDesMappings(Long schemaMetadataId) {
        List<QueryParam> queryParams =
                Collections.singletonList(new QueryParam(SchemaSerDesMapping.SCHEMA_METADATA_ID, schemaMetadataId.toString()));

        return storageManager.find(SchemaSerDesMapping.NAMESPACE, queryParams);
    }

    @Override
    public Collection<SerDesInfo> getSchemaDeserializers(Long schemaMetadataId) {
        return getSerDesInfos(schemaMetadataId, false);
    }

    private List<SerDesInfo> getSerDesInfos(Long schemaMetadataId, boolean isSerializer) {
        Collection<SchemaSerDesMapping> schemaSerDesMappings = getSchemaSerDesMappings(schemaMetadataId);
        List<SerDesInfo> serDesInfos;
        if (schemaSerDesMappings == null || schemaSerDesMappings.isEmpty()) {
            serDesInfos = Collections.emptyList();
        } else {
            serDesInfos = new ArrayList<>();
            for (SchemaSerDesMapping schemaSerDesMapping : schemaSerDesMappings) {
                SerDesInfo serDesInfo = getSerDesInfo(schemaSerDesMapping.getSerDesId());
                if ((isSerializer && serDesInfo.getIsSerializer())
                        || !serDesInfo.getIsSerializer()) {
                    serDesInfos.add(serDesInfo);
                }
            }
        }
        return serDesInfos;
    }

    @Override
    public InputStream downloadJar(Long serDesId) {
        InputStream inputStream = null;

        SerDesInfo serDesInfoStorable = getSerDesInfo(serDesId);
        if (serDesInfoStorable != null) {
            try {
                inputStream = fileStorage.downloadFile(serDesInfoStorable.getFileId());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return inputStream;
    }

    @Override
    public void mapSerDesWithSchema(Long schemaMetadataId, Long serDesId) {
        SerDesInfo serDesInfo = getSerDesInfo(serDesId);
        if (serDesInfo == null) {
            throw new SerDesException("Serializer with given ID " + serDesId + " does not exist");
        }

        SchemaSerDesMapping schemaSerDesMapping = new SchemaSerDesMapping(schemaMetadataId, serDesId);
        storageManager.add(schemaSerDesMapping);
    }

    public static class Options {
        // we may want to remove schema.registry prefix from configuration properties as these are all properties
        // given by client.
        public static final String SCHEMA_CACHE_SIZE = "schema.cache.size";
        public static final String SCHEMA_CACHE_EXPIRY_INTERVAL_SECS = "schema.cache.expiry.interval";
        public static final int DEFAULT_SCHEMA_CACHE_SIZE = 10000;
        public static final long DEFAULT_SCHEMA_CACHE_EXPIRY_INTERVAL_SECS = 60 * 60L;

        private final Map<String, ?> config;

        public Options(Map<String, ?> config) {
            this.config = config;
        }

        private Object getPropertyValue(String propertyKey, Object defaultValue) {
            Object value = config.get(propertyKey);
            return value != null ? value : defaultValue;
        }

        public int getMaxSchemaCacheSize() {
            return (Integer) getPropertyValue(SCHEMA_CACHE_SIZE, DEFAULT_SCHEMA_CACHE_SIZE);
        }

        public long getSchemaExpiryInSecs() {
            return (Long) getPropertyValue(SCHEMA_CACHE_EXPIRY_INTERVAL_SECS, DEFAULT_SCHEMA_CACHE_EXPIRY_INTERVAL_SECS);
        }
    }

}