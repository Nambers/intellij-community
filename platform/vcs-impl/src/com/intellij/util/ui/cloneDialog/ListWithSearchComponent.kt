// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.ui.cloneDialog

import org.jetbrains.annotations.ApiStatus.ScheduledForRemoval

@ScheduledForRemoval
@Deprecated("Replaced with simpler and more flexible version [com.intellij.collaboration.ui.CollaborationToolsUIUtil.attachSearch]")
interface SearchableListItem {
  val stringToSearch: String?
}