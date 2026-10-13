package dev.macdroid.app

import dev.macdroid.app.platform.SaveChoice
import dev.macdroid.app.platform.SaveMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveModeTest {
    @Test fun defaultIsMediaStoreDownloads() = assertEquals(SaveChoice(SaveMode.Default, false), SaveMode.choose(false, null, false))
    @Test fun askEachTimeWins() = assertEquals(SaveChoice(SaveMode.Ask, false), SaveMode.choose(true, "content://tree", true))
    @Test fun chosenFolderIsUsedWhileWritable() = assertEquals(SaveChoice(SaveMode.Folder, false), SaveMode.choose(false, "content://tree", true))

    // Review Focus #3
    @Test fun revokedFolderFallsBackToAskWithProblem() = assertEquals(SaveChoice(SaveMode.Ask, true), SaveMode.choose(false, "content://tree", false))
}
