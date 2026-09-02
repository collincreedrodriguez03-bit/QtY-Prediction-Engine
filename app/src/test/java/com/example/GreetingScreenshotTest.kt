package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.engine.EngineState
import com.example.engine.IndicatorSnapshot
import com.example.engine.PredictionRecord
import com.example.ui.MainUiState
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun qty_ui_screenshot() {
    val snapshot = IndicatorSnapshot(
      ema9 = 65200.0,
      ema21 = 64950.0,
      rsi = 74.0,
      momentum = 250.0,
      velocity = 30.0,
      acceleration = 4.0,
      volatility = 35.0,
      volume = 12.5,
      volumeChange = 1.6,
      buffer = 180.0,
      bidAskSpread = 0.5,
      exchangeAgreement = "STRONG_AGREEMENT",
      formulaDisplay = "72EMA + 65RSI + 80MOM + 55VEL + 50VOL + 52BUF = 68% -> UP"
    )

    val samplePrediction = PredictionRecord(
      inputs = snapshot,
      decision = "UP",
      score = 0.68,
      strength = "MEDIUM",
      predictedPrice = 65150.0,
      currentPrice = 65000.0,
      predictionHorizon = 60
    )

    val dummyState = MainUiState(
      engineState = EngineState(
        isRunning = true,
        cycleCount = 42,
        latestPrice = 65000.0,
        latestTimestamp = System.currentTimeMillis(),
        latestExchange = "BINANCE",
        krakenPrice = 65010.0,
        latestSnapshot = snapshot,
        latestPrediction = samplePrediction,
        recentPredictions = listOf(samplePrediction),
        mathDisplay = "72EMA + 65RSI + 80MOM + 55VEL + 50VOL + 52BUF = 68% -> UP"
      )
    )

    composeTestRule.setContent {
      MyApplicationTheme(darkTheme = true) {
        QtYAppScreen(
          uiState = dummyState,
          onToggleEngine = {},
          onSingleCycle = {},
          onRunBacktest = {},
          onTabSelected = {},
          onCopyJson = {}
        )
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}
