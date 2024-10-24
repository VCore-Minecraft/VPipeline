package de.verdox.vpipeline.api.pipeline.datatypes;

import com.google.gson.InstanceCreator;
import com.google.gson.JsonElement;
import de.verdox.vpipeline.api.NetworkLogger;
import de.verdox.vpipeline.api.modules.AttachedPipeline;
import de.verdox.vpipeline.api.pipeline.annotations.PipelineDataProperties;
import de.verdox.vpipeline.api.pipeline.core.Pipeline;
import de.verdox.vpipeline.api.util.AnnotationResolver;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@PipelineDataProperties
public abstract class PipelineData implements IPipelineData {
    private final UUID objectUUID;
    private transient final DataSynchronizer dataSynchronizer;
    private transient final long cleanTime;
    private transient final TimeUnit cleanTimeUnit;
    private transient long lastUse = System.currentTimeMillis();
    private transient final AttachedPipeline attachedPipeline;

    public PipelineData(@NotNull Pipeline pipeline, @NotNull UUID objectUUID) {
        this.attachedPipeline = new AttachedPipeline(gsonBuilder -> gsonBuilder
                .setPrettyPrinting()
                .serializeNulls()
                .registerTypeAdapter(getClass(), (InstanceCreator<PipelineData>) type -> this)
                .create());
        this.attachedPipeline.attachPipeline(pipeline);
        Objects.requireNonNull(pipeline, "pipeline can't be null!");
        Objects.requireNonNull(objectUUID, "objectUUID can't be null!");

        this.objectUUID = objectUUID;
        if (pipeline.getSynchronizingService() != null)
            this.dataSynchronizer = pipeline.getSynchronizingService().getOrCreate(pipeline, this);
        else
            this.dataSynchronizer = new DummyDataDataSynchronizer(getClass());
        PipelineDataProperties dataProperties = AnnotationResolver.getDataProperties(getClass());
        this.cleanTime = dataProperties.time();
        this.cleanTimeUnit = dataProperties.timeUnit();
    }

    @Override
    public UUID getObjectUUID() {
        return objectUUID;
    }

    @Override
    public JsonElement serialize() {
        try {
            return attachedPipeline.getGson().toJsonTree(this);
        } catch (Throwable e) {
            NetworkLogger.warning("Error while serializing " + getObjectUUID() + " | " + getClass().getSimpleName());
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    @Override
    public void deserialize(JsonElement jsonObject) {
        try {
            if (AnnotationResolver.getDataProperties(getClass()).debugMode())
                NetworkLogger.debug("Updating " + this);
            attachedPipeline.getGson().fromJson(jsonObject, getClass());
        } catch (Throwable e) {
            NetworkLogger.warning("Error while deserializing " + getObjectUUID() + " | " + getClass().getSimpleName());
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    @Override
    public @NotNull DataSynchronizer getSynchronizer() {
        return dataSynchronizer;
    }

    @Override
    public void updateLastUsage() {
        lastUse = System.currentTimeMillis();
    }

    @Override
    public void save(boolean saveToStorage) {
        updateLastUsage();
        attachedPipeline.getAttachedPipeline().getPipelineSynchronizer().sync(this, saveToStorage);
    }

    public static <S extends IPipelineData> S instantiateData(@NotNull Pipeline pipeline, @NotNull Class<? extends S> dataClass, @NotNull UUID objectUUID) {
        try {
            S dataObject = dataClass
                    .getDeclaredConstructor(Pipeline.class, UUID.class)
                    .newInstance(pipeline, objectUUID);
            dataObject.updateLastUsage();
            return dataClass.cast(dataObject);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                 NoSuchMethodException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public AttachedPipeline getAttachedPipeline() {
        return attachedPipeline;
    }

    static class DummyDataDataSynchronizer implements DataSynchronizer {

        private final Class<? extends IPipelineData> type;

        public DummyDataDataSynchronizer(Class<? extends IPipelineData> type){
            this.type = type;
        }

        @Override
        public void shutdown() {

        }

        @Override
        public void cleanUp() {

        }

        @Override
        public void pushUpdate(@NotNull IPipelineData data) {
            NetworkLogger.debug("[" + data.getAttachedPipeline().getAttachedPipeline().getNetworkParticipant().getIdentifier() + "] Syncing with dummy data synchronizer");
        }

        @Override
        public int sendDataBlockToNetwork(DataBlock dataBlock) {
            return 0;
        }

        @Override
        public AttachedPipeline getAttachedPipeline() {
            return getAttachedPipeline();
        }

        @Override
        public UUID getSynchronizerUUID() {
            return UUID.randomUUID();
        }

        @Override
        public Class<? extends IPipelineData> getSynchronizingType() {
            return type;
        }

        @Override
        public void connect() {

        }

        @Override
        public void disconnect() {

        }
    }
}
