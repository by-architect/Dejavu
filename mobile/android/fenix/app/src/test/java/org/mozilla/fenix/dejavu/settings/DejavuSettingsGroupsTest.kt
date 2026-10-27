/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import io.mockk.every
import io.mockk.mockk
import mozilla.components.service.fxrelay.eligibility.Eligible
import mozilla.components.service.fxrelay.eligibility.Ineligible
import mozilla.components.service.fxrelay.eligibility.RelayEligibilityStore
import mozilla.components.service.fxrelay.eligibility.RelayState
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.settings.requirePreference
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DejavuSettingsGroupsTest {
    private val tabsWorkspaces = DejavuTabsWorkspacesFragment()
    private val passwordsLanguage = DejavuPasswordsLanguageFragment()

    @Before
    fun setup() {
        every { testContext.components.settings } returns mockk(relaxed = true)
        every { testContext.components.core.store.state.translationEngine } returns mockk(relaxed = true)

        val activity = Robolectric.buildActivity(FragmentActivity::class.java).create().get()
        activity.supportFragmentManager.beginTransaction()
            .add(tabsWorkspaces, "tabs and workspaces")
            .add(passwordsLanguage, "passwords and language")
            .commitNow()
    }

    @Test
    fun `every entry of the two screens opens a screen`() {
        listOf(tabsWorkspaces, passwordsLanguage).forEach { fragment ->
            val keys = fragment.entries.map { testContext.getString(it.key) }
            val preferences = fragment.preferenceScreen.all()
            assertTrue(preferences.isNotEmpty())
            preferences.forEach { assertTrue("${it.key} opens nothing", it.key in keys) }
            assertEquals(keys.toSet(), preferences.map { it.key }.toSet())
        }
    }

    @Test
    fun `the homepage entry tells that the homepage opens after four hours`() {
        val homepagePreference = tabsWorkspaces.requirePreference<Preference>(R.string.pref_key_home)
        every { testContext.components.settings.alwaysOpenTheHomepageWhenOpeningTheApp } returns false
        every { testContext.components.settings.openHomepageAfterFourHoursOfInactivity } returns true
        every { testContext.components.settings.alwaysOpenTheLastTabWhenOpeningTheApp } returns false
        assertTrue(homepagePreference.summary.isNullOrEmpty())
        val summary = testContext.getString(R.string.opening_screen_after_four_hours_of_inactivity_summary)

        tabsWorkspaces.setupHomepagePreference(testContext.components.settings)

        assertEquals(summary, homepagePreference.summary)
    }

    @Test
    fun `the homepage entry tells that the last tab opens`() {
        val homepagePreference = tabsWorkspaces.requirePreference<Preference>(R.string.pref_key_home)
        every { testContext.components.settings.alwaysOpenTheHomepageWhenOpeningTheApp } returns false
        every { testContext.components.settings.openHomepageAfterFourHoursOfInactivity } returns false
        every { testContext.components.settings.alwaysOpenTheLastTabWhenOpeningTheApp } returns true
        assertTrue(homepagePreference.summary.isNullOrEmpty())
        val summary = testContext.getString(R.string.opening_screen_last_tab_summary)

        tabsWorkspaces.setupHomepagePreference(testContext.components.settings)

        assertEquals(summary, homepagePreference.summary)
    }

    @Test
    fun `the homepage entry tells that the homepage always opens`() {
        val homepagePreference = tabsWorkspaces.requirePreference<Preference>(R.string.pref_key_home)
        every { testContext.components.settings.alwaysOpenTheHomepageWhenOpeningTheApp } returns true
        every { testContext.components.settings.openHomepageAfterFourHoursOfInactivity } returns false
        every { testContext.components.settings.alwaysOpenTheLastTabWhenOpeningTheApp } returns false
        assertTrue(homepagePreference.summary.isNullOrEmpty())
        val summary = testContext.getString(R.string.opening_screen_homepage_summary)

        tabsWorkspaces.setupHomepagePreference(testContext.components.settings)

        assertEquals(summary, homepagePreference.summary)
    }

    @Test
    fun `email masks show when they are on and the account can use them`() {
        val preference = passwordsLanguage.requirePreference<Preference>(R.string.pref_key_email_masks)
        every { testContext.components.settings.isEmailMaskFeatureEnabled } returns true
        every { testContext.components.relayEligibilityStore } returns
            RelayEligibilityStore(RelayState(Eligible.Premium))

        passwordsLanguage.setupEmailMaskPreference(testContext.components.settings, testContext.components)

        assertTrue(preference.isVisible)
    }

    @Test
    fun `email masks hide when the account cannot use them`() {
        val preference = passwordsLanguage.requirePreference<Preference>(R.string.pref_key_email_masks)
        every { testContext.components.settings.isEmailMaskFeatureEnabled } returns true
        every { testContext.components.relayEligibilityStore } returns
            RelayEligibilityStore(RelayState(Ineligible.FirefoxAccountNotLoggedIn))

        passwordsLanguage.setupEmailMaskPreference(testContext.components.settings, testContext.components)

        assertFalse(preference.isVisible)
    }

    @Test
    fun `email masks hide when they are off`() {
        val preference = passwordsLanguage.requirePreference<Preference>(R.string.pref_key_email_masks)
        every { testContext.components.settings.isEmailMaskFeatureEnabled } returns false
        every { testContext.components.relayEligibilityStore } returns
            RelayEligibilityStore(RelayState(Eligible.Premium))

        passwordsLanguage.setupEmailMaskPreference(testContext.components.settings, testContext.components)

        assertFalse(preference.isVisible)
    }

    @Test
    fun `email masks hide when they are off and the account cannot use them`() {
        val preference = passwordsLanguage.requirePreference<Preference>(R.string.pref_key_email_masks)
        every { testContext.components.settings.isEmailMaskFeatureEnabled } returns false
        every { testContext.components.relayEligibilityStore } returns
            RelayEligibilityStore(RelayState(Ineligible.FirefoxAccountNotLoggedIn))

        passwordsLanguage.setupEmailMaskPreference(testContext.components.settings, testContext.components)

        assertFalse(preference.isVisible)
    }

    private fun PreferenceGroup.all(): List<Preference> =
        (0 until preferenceCount).map(::getPreference).flatMap { if (it is PreferenceGroup) it.all() else listOf(it) }
}
