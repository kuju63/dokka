/*
 * Copyright 2014-2024 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
package org.jetbrains.dokka.gradle.internal

import org.jetbrains.dokka.gradle.DokkaExtension

// When Dokka is applied to a build script Gradle will auto-generate these accessors

internal fun DokkaExtension.versions(configure: DokkaExtension.Versions.() -> Unit) {
    versions.apply(configure)
}
