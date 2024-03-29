package model.data;

import de.verdox.vpipeline.api.pipeline.annotations.DataStorageIdentifier;
import de.verdox.vpipeline.api.pipeline.annotations.PipelineDataProperties;
import de.verdox.vpipeline.api.pipeline.core.Pipeline;
import de.verdox.vpipeline.api.pipeline.datatypes.PipelineData;
import de.verdox.vpipeline.api.pipeline.enums.DataContext;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

@DataStorageIdentifier(identifier = "model.data.OnlyStorageData")
@PipelineDataProperties(dataContext = DataContext.STORAGE_ONLY)
public class OnlyStorageData extends PipelineData {
    public OnlyStorageData(@NotNull Pipeline pipeline, @NotNull UUID objectUUID) {
        super(pipeline, objectUUID);
    }
}
