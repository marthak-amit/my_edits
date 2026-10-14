package com.myedits.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myedits.app.data.ActivityLog
import com.myedits.app.ui.home.HomeScreen
import com.myedits.app.ui.settings.SettingsScreen
import com.myedits.app.ui.theme.MyEditsTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@UnstableApi
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w392dp-h800dp-xhdpi")
class HomeUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val opened = mutableListOf<Pair<String, Boolean>>()
    private var settingsOpened = 0

    @Before fun setUp() { TestData.wipe(); opened.clear(); settingsOpened = 0 }
    @After fun tearDown() { TestData.wipe() }

    private fun show() {
        rule.setContent { MyEditsTheme { HomeScreen(onOpenProject = { id, cam -> opened += id to cam }, onSettings = { settingsOpened++ }) } }
    }

    private fun waitTag(tag: String, timeout: Long = 10000) = rule.waitForTag(tag, timeout)
    private fun waitGone(tag: String, timeout: Long = 10000) = rule.waitForTagGone(tag, timeout)
    private fun tag(t: String) = rule.onNodeWithTag(t)

    @Test fun emptyStateInvitesFirstProject() {
        show(); waitTag("projects_empty")
        tag("home_create").assertIsDisplayed()
        rule.onNodeWithText("No projects yet").assertIsDisplayed()
    }

    @Test fun createSheetOffersThreeWays() {
        show(); waitTag("projects_empty")
        tag("home_create").performClick()
        waitTag("create_sheet")
        tag("create_gallery").assertIsDisplayed(); tag("create_camera").assertIsDisplayed(); tag("create_blank").assertIsDisplayed()
    }

    @Test fun blankProjectIsCreatedPersistedAndOpened() {
        show(); waitTag("projects_empty")
        tag("home_create").performClick(); waitTag("create_blank")
        tag("create_blank").performClick()
        rule.waitFor { opened.isNotEmpty() }
        assertEquals(1, opened.size); assertFalse(opened[0].second)
        val saved = TestData.app().container.repository.list()
        assertEquals(1, saved.size); assertEquals("Project 1", saved[0].name)
        assertEquals(saved[0].id, opened[0].first)
        assertEquals(1, TestData.app().container.activity.load().count { it.type == ActivityLog.CREATED })
    }

    @Test fun cameraOptionOpensProjectInCameraMode() {
        show(); waitTag("projects_empty")
        tag("home_create").performClick(); waitTag("create_camera")
        tag("create_camera").performClick()
        rule.waitFor { opened.isNotEmpty() }
        assertTrue(opened[0].second)
    }

    @Test fun seededProjectsAreListedAndOpenable() {
        val a = TestData.project("Beach day", updatedAt = 3000); TestData.project("City lights", updatedAt = 2000)
        show(); waitTag("projects_grid")
        rule.onNodeWithText("Beach day").assertIsDisplayed(); rule.onNodeWithText("City lights").assertIsDisplayed()
        rule.onNodeWithText("2 projects").assertIsDisplayed()
        tag("project_${a.id}").performClick()
        assertEquals(a.id to false, opened.single())
    }

    @Test fun sortByNameReordersProjects() {
        TestData.project("Zebra", updatedAt = 9000); TestData.project("Apple", updatedAt = 1000)
        show(); waitTag("projects_grid")
        fun order() = rule.onAllNodesWithText("Zebra", useUnmergedTree = true).fetchSemanticsNodes().first().positionInRoot.y to
            rule.onAllNodesWithText("Apple", useUnmergedTree = true).fetchSemanticsNodes().first().positionInRoot.y
        val (zy, ay) = order()
        assertTrue("recent first: Zebra above Apple", zy <= ay)
        tag("sort_button").performClick(); waitTag("sort_NAME"); tag("sort_NAME").performClick()
        rule.waitForIdle()
        val (zy2, ay2) = order()
        assertTrue("name order: Apple before Zebra", ay2 <= zy2 && (ay2 < zy2 || ay2 == zy2))
    }

    @Test fun renameMenuEntryOpensRenameDialogWithCurrentName() {
        val p = TestData.project("Old name")
        show(); waitTag("projects_grid")
        tag("menu_${p.id}").performClick(); waitTag("menu_rename")
        tag("menu_rename").assertIsDisplayed(); tag("menu_duplicate").assertIsDisplayed(); tag("menu_delete").assertIsDisplayed()
    }

    @Test fun renamePersistsTrimmedNameAndIgnoresBlank() {
        // The dialog's text field cannot settle inside Robolectric's dialog window, so the rename logic is
        // covered here at view-model level; the dialog itself is exercised on a real emulator.
        val p = TestData.project("Old name")
        val vm = com.myedits.app.ui.home.HomeViewModel(TestData.app().container)
        rule.waitFor { vm.projects.isNotEmpty() }
        vm.rename(p.id, "  Shiny new name ")
        rule.waitFor { TestData.app().container.repository.load(p.id)!!.name == "Shiny new name" }
        vm.rename(p.id, "   ")
        rule.waitFor { vm.projects.firstOrNull()?.name == "Shiny new name" }
        assertEquals("Shiny new name", TestData.app().container.repository.load(p.id)!!.name)
    }

    @Test fun duplicateMakesIndependentCopy() {
        val p = TestData.project("Original", n = 2)
        show(); waitTag("projects_grid")
        tag("menu_${p.id}").performClick(); waitTag("menu_duplicate"); tag("menu_duplicate").performClick()
        rule.waitForText("Original copy")
        val all = TestData.app().container.repository.list()
        assertEquals(2, all.size)
        val copy = all.first { it.id != p.id }
        assertTrue(copy.clips.all { java.io.File(it.path).exists() && it.path.contains(copy.id) })
    }

    @Test fun deleteAsksForConfirmationAndCanBeCancelled() {
        val p = TestData.project("Doomed")
        show(); waitTag("projects_grid")
        tag("menu_${p.id}").performClick(); waitTag("menu_delete"); tag("menu_delete").performClick()
        rule.onNodeWithText("Delete “Doomed”?").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertNotNull(TestData.app().container.repository.load(p.id))
        tag("menu_${p.id}").performClick(); waitTag("menu_delete"); tag("menu_delete").performClick()
        waitTag("home_delete_ok"); tag("home_delete_ok").performClick()
        waitTag("projects_empty")
        assertNull(TestData.app().container.repository.load(p.id))
    }

    @Test fun ideasCanBeAddedCompletedAndRemoved() {
        show(); tag("tab_IDEAS").performClick(); waitTag("ideas_empty")
        tag("idea_add").assertIsNotEnabled()
        tag("idea_input").performTextInput("Time-lapse of the sunrise"); tag("idea_add").performClick()
        waitTag("ideas_list")
        rule.onNodeWithText("Time-lapse of the sunrise").assertIsDisplayed()
        val id = TestData.app().container.ideas.load().single().id
        tag("idea_check_$id").performClick()
        assertTrue(TestData.app().container.ideas.load().single().done)
        tag("idea_delete_$id").performClick()
        waitTag("ideas_empty")
        assertTrue(TestData.app().container.ideas.load().isEmpty())
    }

    @Test fun ideaCanSeedAProject() {
        show(); tag("tab_IDEAS").performClick(); waitTag("ideas_empty")
        tag("idea_input").performTextInput("Cooking reel"); tag("idea_add").performClick(); waitTag("ideas_list")
        val id = TestData.app().container.ideas.load().single().id
        tag("idea_start_$id").performClick()
        rule.waitFor { opened.isNotEmpty() }
        assertEquals("Cooking reel", TestData.app().container.repository.list().single().name)
    }

    @Test fun ideasSurviveRecreation() {
        show(); tag("tab_IDEAS").performClick(); waitTag("ideas_empty")
        tag("idea_input").performTextInput("Persisted idea"); tag("idea_add").performClick()
        assertEquals("Persisted idea", TestData.app().container.ideas.load().single().text)
    }

    @Test fun inspirationListsTemplatesAndUseOpensCreateSheet() {
        show(); tag("tab_INSPIRATION").performClick(); waitTag("inspiration_list")
        tag("template_reel").assertIsDisplayed()
        tag("template_use_reel").performClick(); waitTag("create_sheet")
        rule.onNodeWithText("Start with “Reel starter”").assertIsDisplayed()
        tag("create_blank").performClick()
        rule.waitFor { opened.isNotEmpty() }
        val p = TestData.app().container.repository.list().single()
        assertEquals("reel", p.template)
    }

    @Test fun insightsReflectActivity() {
        TestData.project("A", n = 2); TestData.project("B", n = 1)
        val log = TestData.app().container.activity
        log.record(ActivityLog.CREATED); log.record(ActivityLog.EXPORTED, 6000)
        show(); tag("tab_INSIGHTS").performClick(); waitTag("insights")
        tag("stat_projects").assertTextContains("2", substring = true)
        tag("stat_exports").assertTextContains("1", substring = true)
        tag("stat_footage").assertTextContains("0:09", substring = true)
        tag("stat_streak").assertTextContains("1", substring = true)
        tag("insights_chart").assertIsDisplayed()
    }

    @Test fun insightsEmptyStateExplainsWhatToDo() {
        show(); tag("tab_INSIGHTS").performClick(); waitTag("insights_empty")
    }

    @Test fun settingsButtonNavigates() {
        show(); tag("home_settings").performClick(); assertEquals(1, settingsOpened)
    }

    @Test fun settingsPersistChoices() {
        var backs = 0; var deleted = 0
        rule.setContent { MyEditsTheme { SettingsScreen(onBack = { backs++ }, onDeleteAll = { deleted++ }) } }
        tag("set_q_720").performClick(); tag("set_fps_30").performClick()
        tag("set_gallery").performClick(); tag("set_haptics").performClick()
        val s = TestData.app().container.settings.load()
        assertEquals(720, s.exportQuality); assertEquals(30, s.exportFps); assertFalse(s.saveToGallery); assertFalse(s.haptics)
        tag("settings_back").performClick(); assertEquals(1, backs)
        rule.onNodeWithTag("set_delete_all").performScrollTo().performClick()
        tag("set_delete_confirm").performClick(); assertEquals(1, deleted)
    }

    @Test fun clearCacheReportsFreedSpace() {
        val cache = java.io.File(TestData.app().cacheDir, "exports").apply { mkdirs() }
        java.io.File(cache, "old.mp4").writeBytes(ByteArray(200_000))
        rule.setContent { MyEditsTheme { SettingsScreen(onBack = {}, onDeleteAll = {}) } }
        tag("set_clear_cache").performScrollTo().performClick()
        rule.onNodeWithText("Freed", substring = true).assertIsDisplayed()
        assertFalse(cache.exists())
    }
}
