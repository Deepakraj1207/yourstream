package dev.yourstream.search.engine

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SearchModule {
    @Binds abstract fun search(impl: LibrarySearchEngine): LibrarySearch
}
