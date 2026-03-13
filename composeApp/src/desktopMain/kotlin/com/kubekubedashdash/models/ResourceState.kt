package com.kubekubedashdash.models

sealed class ResourceState<out T> {
    data object Loading : ResourceState<Nothing>()

    data class Error(
        val message: String,
    ) : ResourceState<Nothing>()

    data class Success<T>(
        val data: T,
    ) : ResourceState<T>()
}
