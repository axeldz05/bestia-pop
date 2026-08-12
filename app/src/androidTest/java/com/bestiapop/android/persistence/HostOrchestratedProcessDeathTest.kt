package com.bestiapop.android.persistence

/**
 * Marks instrumentation tests that require host-side process orchestration.
 *
 * Gradle excludes this category from normal connected runs. The process-death host script invokes
 * each phase directly through `am instrument`.
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class HostOrchestratedProcessDeathTest
