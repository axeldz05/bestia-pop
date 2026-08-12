package com.bestiapop.android.testutil

/**
 * JUnit 4 category markers for JVM tests.
 *
 * Use [org.junit.experimental.categories.Category] on a class. Android instrumented tests should
 * use the equivalent `androidx.test.filters` size annotation; live-network tests use
 * [LiveNetworkTest] and must not be part of the hermetic PR suite.
 */
interface SmallTest

interface MediumTest

interface LargeTest

interface LiveNetworkTest
