package com.treadz.console.treadmill

import org.junit.jupiter.api.Assertions.fail

/** JUnit's `assertNotNull` returns Unit: this variant returns the value. */
fun <T : Any> notNull(value: T?, message: String = "null value"): T = value ?: fail(message)
