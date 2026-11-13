package com.jarvis.assistant.ai

import com.google.common.truth.Truth.assertThat
import com.jarvis.assistant.core.model.DeviceInfoField
import com.jarvis.assistant.core.model.JarvisAction
import com.jarvis.assistant.core.model.SettingsPanel
import com.jarvis.assistant.security.ParseResult
import com.jarvis.assistant.security.RejectionCode
import org.junit.Test

class LocalCommandParserTest {

    private val parser = LocalCommandParser()

    private fun action(input: String): JarvisAction =
        (parser.parse(input) as ParseResult.Parsed).action

    @Test
    fun `opens apps in english`() {
        assertThat(action("open camera")).isEqualTo(JarvisAction.OpenApp("camera"))
        assertThat(action("launch spotify")).isEqualTo(JarvisAction.OpenApp("spotify"))
    }

    @Test
    fun `opens apps with hinglish word order`() {
        assertThat(action("whatsapp kholo")).isEqualTo(JarvisAction.OpenApp("whatsapp"))
        assertThat(action("youtube khol do")).isEqualTo(JarvisAction.OpenApp("youtube"))
        assertThat(action("camera chalu karo")).isEqualTo(JarvisAction.OpenApp("camera"))
    }

    @Test
    fun `routes connectivity phrases to the settings panel`() {
        assertThat(action("wifi settings kholo")).isEqualTo(JarvisAction.OpenSettings(SettingsPanel.WIFI))
        assertThat(action("open bluetooth settings")).isEqualTo(JarvisAction.OpenSettings(SettingsPanel.BLUETOOTH))
    }

    @Test
    fun `sets and adjusts volume`() {
        assertThat(action("set volume to 40").type.wire).isEqualTo("SET_VOLUME")
        assertThat(action("awaaz kam karo").type.wire).isEqualTo("ADJUST_VOLUME")
        assertThat(action("mute")).isEqualTo(JarvisAction.SetVolume(com.jarvis.assistant.core.model.AudioStream.MUSIC, 0))
    }

    @Test
    fun `parses alarms including am pm and hindi time words`() {
        assertThat(action("set alarm for 7:30")).isEqualTo(JarvisAction.CreateAlarm(7, 30, null))
        assertThat(action("alarm 6:15 pm")).isEqualTo(JarvisAction.CreateAlarm(18, 15, null))
        assertThat(action("subah 7 baje ka alarm lagao")).isEqualTo(JarvisAction.CreateAlarm(7, 0, null))
    }

    @Test
    fun `parses timers with units`() {
        assertThat(action("timer 10 minutes")).isEqualTo(JarvisAction.StartTimer(600, null))
        assertThat(action("set a timer for 30 seconds")).isEqualTo(JarvisAction.StartTimer(30, null))
        assertThat(action("1 hour ka timer lagao")).isEqualTo(JarvisAction.StartTimer(3600, null))
    }

    @Test
    fun `answers device questions locally`() {
        assertThat(action("battery batao")).isEqualTo(JarvisAction.DeviceInfo(DeviceInfoField.BATTERY))
        assertThat(action("how much storage is left"))
            .isEqualTo(JarvisAction.DeviceInfo(DeviceInfoField.STORAGE))
        assertThat(action("ram status")).isEqualTo(JarvisAction.DeviceInfo(DeviceInfoField.MEMORY))
    }

    @Test
    fun `manages tasks`() {
        assertThat(action("add task pay rent")).isEqualTo(JarvisAction.AddTask("pay rent", null))
        assertThat(action("show my tasks")).isEqualTo(JarvisAction.ListTasks)
        assertThat(action("complete task pay rent")).isEqualTo(JarvisAction.CompleteTask("pay rent"))
    }

    @Test
    fun `remembers and recalls facts`() {
        assertThat(action("remember I prefer filter coffee"))
            .isEqualTo(JarvisAction.Remember("i prefer filter coffee"))
        assertThat(action("recall coffee")).isEqualTo(JarvisAction.Recall("coffee"))
    }

    @Test
    fun `prepares calls only for a named contact`() {
        assertThat(action("call mom")).isEqualTo(JarvisAction.CallContact("mom"))
        assertThat(action("papa ko call karo")).isEqualTo(JarvisAction.CallContact("papa"))
    }

    @Test
    fun `rejects anything it does not understand instead of guessing`() {
        val result = parser.parse("book me a flight to goa")
        assertThat(result).isInstanceOf(ParseResult.Rejected::class.java)
        assertThat((result as ParseResult.Rejected).code).isEqualTo(RejectionCode.UNKNOWN_ACTION)
    }

    @Test
    fun `rejects empty input`() {
        val result = parser.parse("   ")
        assertThat((result as ParseResult.Rejected).code).isEqualTo(RejectionCode.MALFORMED)
    }

    @Test
    fun `every advertised local example parses`() {
        parser.supportedExamples.forEach { example ->
            assertThat(parser.parse(example)).isInstanceOf(ParseResult.Parsed::class.java)
        }
    }
}
