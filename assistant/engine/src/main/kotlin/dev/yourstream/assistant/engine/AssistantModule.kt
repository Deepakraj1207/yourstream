package dev.yourstream.assistant.engine

import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.yourstream.assistant.core.IntentModel

@Module
@InstallIn(SingletonComponent::class)
abstract class AssistantModule {
    @Binds abstract fun assistant(impl: LocalMediaAssistant): MediaAssistant

    /** No on-device model ships; a build that adds one binds [IntentModel] and it is used automatically. */
    @BindsOptionalOf abstract fun model(): IntentModel
}
