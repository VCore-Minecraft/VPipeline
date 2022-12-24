package de.verdox.vpipeline.api.modules.redis.globalcache;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import de.verdox.vpipeline.api.NetworkLogger;
import de.verdox.vpipeline.api.modules.AttachedPipeline;
import de.verdox.vpipeline.api.pipeline.annotations.PipelineDataProperties;
import de.verdox.vpipeline.api.pipeline.datatypes.IPipelineData;
import de.verdox.vpipeline.api.pipeline.parts.DataProviderLock;
import de.verdox.vpipeline.api.pipeline.parts.GlobalCache;
import de.verdox.vpipeline.api.pipeline.parts.RemoteStorage;
import de.verdox.vpipeline.api.util.AnnotationResolver;
import de.verdox.vpipeline.impl.util.RedisConnection;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RBucket;
import org.redisson.client.codec.StringCodec;

import javax.annotation.Nonnull;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;

public class RedisCache extends RedisConnection implements GlobalCache, RemoteStorage {
    private final AttachedPipeline attachedPipeline;


    public RedisCache(boolean clusterMode, @NotNull String[] addressArray, String redisPassword) {
        super(clusterMode, addressArray, redisPassword);
        this.attachedPipeline = new AttachedPipeline(GsonBuilder::create);
        NetworkLogger.info("Redis GlobalCache connected");
    }

    @Override
    public synchronized JsonElement loadData(@NotNull Class<? extends IPipelineData> dataClass, @NotNull UUID objectUUID) {
        verifyInput(dataClass, objectUUID);
        try {
            return JsonParser.parseString(getObjectCache(dataClass, objectUUID).get()).getAsJsonObject();
        } catch (Exception e) {
            e.printStackTrace();
            remove(dataClass, objectUUID);
            return null;
        }
    }

    @Override
    public synchronized boolean dataExist(@NotNull Class<? extends IPipelineData> dataClass, @NotNull UUID objectUUID) {
        verifyInput(dataClass, objectUUID);
        var objectCache = getObjectCache(dataClass, objectUUID);
        return objectCache.isExists();
    }

    @Override
    public synchronized void save(@NotNull Class<? extends IPipelineData> dataClass, @NotNull UUID objectUUID, @NotNull JsonElement dataToSave) {
        verifyInput(dataClass, objectUUID);
        RBucket<String> objectCache = getObjectCache(dataClass, objectUUID);
        objectCache.set(attachedPipeline.getGson().toJson(dataToSave));
        NetworkLogger.fine("[RedisCache] Saving to redis cache " + dataClass.getSimpleName() + " [" + objectCache + "]");
        updateExpireTime(dataClass, objectCache);
    }

    @Override
    public synchronized boolean remove(@NotNull Class<? extends IPipelineData> dataClass, @NotNull UUID objectUUID) {
        verifyInput(dataClass, objectUUID);
        RBucket<String> objectCache = getObjectCache(dataClass, objectUUID);
        NetworkLogger.fine("[RedisCache] Removing from redis cache " + dataClass.getSimpleName() + " [" + objectCache + "]");
        return objectCache.delete();
    }

    @Override
    public synchronized Set<UUID> getSavedUUIDs(@NotNull Class<? extends IPipelineData> dataClass) {
        Objects.requireNonNull(dataClass, "dataClass can't be null!");

        return getKeys(dataClass)
                .stream()
                .map(s -> parseUUIDWithKey(dataClass, s))
                .collect(Collectors.toSet());
    }

    @Override
    public synchronized AttachedPipeline getAttachedPipeline() {
        return attachedPipeline;
    }

    @Override
    public DataProviderLock getDataProviderLock() {
        return null;
    }

    private synchronized RBucket<String> getObjectCache(@Nonnull Class<? extends IPipelineData> dataClass, @Nonnull @NotNull UUID objectUUID) {
        verifyInput(dataClass, objectUUID);

        String classifier = AnnotationResolver
                .getDataStorageClassifier(dataClass)
                .isEmpty() ? "" : AnnotationResolver.getDataStorageClassifier(dataClass) + ":";
        String key = "VPipeline:" + classifier + objectUUID + ":" + AnnotationResolver.getDataStorageIdentifier(dataClass);

        RBucket<String> objectCache = redissonClient.getBucket(key, new StringCodec());
        updateExpireTime(dataClass, objectCache);

        return objectCache;
    }

    private Set<String> getKeys(Class<? extends IPipelineData> dataClass) {
        Objects.requireNonNull(dataClass, "dataClass can't be null!");
        String storageIdentifier = AnnotationResolver.getDataStorageIdentifier(dataClass);
        String classifier = AnnotationResolver.getDataStorageClassifier(dataClass);
        return redissonClient.getKeys().getKeysStream().filter(s -> {
            String[] parts = s.split(":");
            if (parts[0].equalsIgnoreCase("lock"))
                return false;

            if (classifier.isEmpty())
                return parts[2].equals(storageIdentifier);
            else
                return parts[1].equals(classifier) && parts[3].equals(storageIdentifier);

        }).collect(Collectors.toSet());
    }

    private void updateExpireTime(@NotNull Class<? extends IPipelineData> dataClass, RBucket<?> bucket) {
        Objects.requireNonNull(dataClass, "dataClass can't be null!");
        PipelineDataProperties properties = AnnotationResolver.getDataProperties(dataClass);

        if (bucket == null)
            return;

        if (properties.cleanOnNoUse())
            bucket.expire(java.time.Duration.ofSeconds(properties.timeUnit().toSeconds(properties.time())));
    }

    private void verifyInput(@Nonnull Class<? extends IPipelineData> dataClass, @Nonnull @NotNull UUID objectUUID) {
        Objects.requireNonNull(dataClass, "dataClass can't be null!");
        Objects.requireNonNull(objectUUID, "objectUUID can't be null!");
    }

    @Override
    public void connect() {

    }

    @Override
    public void disconnect() {
        this.redissonClient.shutdown();
    }

    @Override
    public void shutdown() {
        disconnect();
    }

    @Override
    public <T extends IPipelineData> Lock acquireGlobalObjectReadLock(@NotNull Class<? extends T> dataClass, @NotNull UUID objectUUID) {
        verifyInput(dataClass, objectUUID);
        String storageIdentifier = AnnotationResolver.getDataStorageIdentifier(dataClass);
        return redissonClient.getReadWriteLock("lock:" + storageIdentifier + ":" + objectUUID).readLock();
    }

    @Override
    public <T extends IPipelineData> Lock acquireGlobalObjectWriteLock(@NotNull Class<? extends T> dataClass, @NotNull UUID objectUUID) {
        verifyInput(dataClass, objectUUID);
        String storageIdentifier = AnnotationResolver.getDataStorageIdentifier(dataClass);
        return redissonClient.getReadWriteLock("lock:" + storageIdentifier + ":" + objectUUID).writeLock();
    }

    private UUID parseUUIDWithKey(Class<? extends IPipelineData> dataClass, String key) {
        String classifier = AnnotationResolver.getDataStorageClassifier(dataClass);
        var uuidString = "";
        if (classifier.isEmpty())
            uuidString = key.split(":")[1];
        else
            uuidString = key.split(":")[2];
        return UUID.fromString(uuidString);
    }
}
