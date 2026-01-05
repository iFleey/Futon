package me.fleey.futon.di

import com.slack.circuit.foundation.Circuit
import me.fleey.futon.ui.feature.history.HistoryPresenterFactory
import me.fleey.futon.ui.feature.history.HistoryUiFactory
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
@ComponentScan("me.fleey.futon")
class CircuitModule {
  @Single
  fun provideCircuit(): Circuit = Circuit.Builder()
    .addPresenterFactory(HistoryPresenterFactory())
    .addUiFactory(HistoryUiFactory())
    .build()
}
