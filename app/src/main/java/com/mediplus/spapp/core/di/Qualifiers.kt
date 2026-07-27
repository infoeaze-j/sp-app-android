package com.mediplus.spapp.core.di

import javax.inject.Qualifier

/** Marks the IO-bound dispatcher (network, disk). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Marks the CPU-bound dispatcher (ML Kit analysis, parsing). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** Marks the main/UI dispatcher. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/** Marks the app-private directory update APKs are downloaded into. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdateCacheDir
