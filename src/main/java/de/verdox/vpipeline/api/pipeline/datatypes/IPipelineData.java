package de.verdox.vpipeline.api.pipeline.datatypes;

import com.google.gson.JsonElement;
import de.verdox.vpipeline.api.modules.AttachedPipeline;
import de.verdox.vpipeline.api.pipeline.datatypes.customtypes.DataReference;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * @version 1.0
 * @Author: Lukas Jonsson (Verdox)
 * @date 18.02.2022 22:57
 */
public interface IPipelineData {

    UUID getObjectUUID();

    /**
     * Executed after a DataManipulator synced the object
     *
     * @param dataBeforeSync The data the object had before syncing
     */
    default void onSync(String dataBeforeSync) {

    }

    /**
     * Executed after instantiation of the Object
     * Executed before Object is put into LocalCache
     */
    default void onCreate() {
    }

    /**
     * Executed before the object is deleted from local cache.
     */
    default void onDelete() {
    }

    /**
     * Executed directly after Data was loaded from Pipeline. Not if it was found in LocalCache
     */
    default void onLoad() {

    }

    /**
     * Executed before onLoad and before onCreate everytime the data is being loaded into local cache.
     * You can use this function to load dependent data from pipeline that is directly associated with this data
     */
    default void loadDependentData() {

    }

    /**
     * Executed before Data is cleared from LocalCache
     */
    default void onCleanUp() {
    }

    default void cleanUp() {
        getSynchronizer().cleanUp();
        onCleanUp();
    }

    JsonElement serialize();

    /**
     * @param jsonObject New Data to deserialize
     * @return The Data before deserialization
     */
    String deserialize(JsonElement jsonObject);
    String deserialize(String jsonString);

    @NotNull
    Synchronizer getSynchronizer();
    void updateLastUsage();
    CompletableFuture<Void> save(boolean saveToStorage);
    AttachedPipeline getAttachedPipeline();
}
