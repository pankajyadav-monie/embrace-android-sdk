package io.embrace.android.embracesdk.testcases.features

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.embrace.android.embracesdk.Severity
import io.embrace.android.embracesdk.internal.toStringMap
import io.embrace.android.embracesdk.testframework.SdkIntegrationTestRule
import io.opentelemetry.kotlin.semconv.UserAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In OTel-only delivery the user identifier must reach the OTel exporters, because Embrace's own envelope
 * metadata (where it used to live) is never sent. Only the identifier is exported, never the user name.
 */
@RunWith(AndroidJUnit4::class)
internal class UserIdOtelExportTest {

    @Rule
    @JvmField
    val testRule: SdkIntegrationTestRule = SdkIntegrationTestRule()

    @Suppress("DEPRECATION") // setUsername (deliberately: it must NOT be exported) and log body
    @Test
    fun `user identifier is exported as user id on spans, logs and the session span`() {
        testRule.runTest(
            testCaseAction = {
                recordSession {
                    embrace.setUserIdentifier("pseudo-123")
                    embrace.setUsername("Jane Doe")
                    embrace.startSpan("after-login").stop()
                    embrace.logMessage("after login", Severity.INFO)
                }
            },
            otelExportAssertion = {
                val span = awaitSpans(1) { it.name == "after-login" }.single()
                assertEquals("pseudo-123", span.attributes.toStringMap()[UserAttributes.USER_ID])

                val log = awaitLogs(1) { it.body.asString() == "after login" }.single()
                assertEquals("pseudo-123", log.attributes.toStringMap()[UserAttributes.USER_ID])

                val session = awaitSpans(1) { it.name == "emb-session" }.single()
                val sessionAttrs = session.attributes.toStringMap()
                assertEquals("pseudo-123", sessionAttrs[UserAttributes.USER_ID])
                assertFalse("user name must not be exported", sessionAttrs.values.contains("Jane Doe"))
                assertFalse("user name must not be exported", span.attributes.toStringMap().values.contains("Jane Doe"))
            }
        )
    }

    @Suppress("DEPRECATION") // log body
    @Test
    fun `cleared user identifier is not exported`() {
        testRule.runTest(
            testCaseAction = {
                recordSession {
                    embrace.setUserIdentifier("pseudo-123")
                    embrace.clearUserIdentifier()
                    embrace.startSpan("after-logout").stop()
                    embrace.logMessage("after logout", Severity.INFO)
                }
            },
            otelExportAssertion = {
                val span = awaitSpans(1) { it.name == "after-logout" }.single()
                assertFalse(span.attributes.toStringMap().containsKey(UserAttributes.USER_ID))
                val log = awaitLogs(1) { it.body.asString() == "after logout" }.single()
                assertFalse(log.attributes.toStringMap().containsKey(UserAttributes.USER_ID))
                val session = awaitSpans(1) { it.name == "emb-session" }.single()
                assertFalse(session.attributes.toStringMap().containsKey(UserAttributes.USER_ID))
            }
        )
    }
}
