package com.emi.tuyeasie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    devicesViewModel: MyDevicesViewModel,
    padding: PaddingValues
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HaCard(title = "Home Assistant") {
            HomeAssistantFields(state, viewModel)
            when {
                state.connectionError != null -> InlineError(state.connectionError)
                state.connectionVerified -> Text(
                    "Connected with administrator access",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (state.testingConnection) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                CardAction("Save and test", viewModel::testConnection, enabled = !state.testingConnection)
            }
        }
        HaCard(title = "Home Wi-Fi") {
            WifiFields(state, viewModel)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                CardAction("Save", viewModel::saveWifi)
            }
        }
        AccountsCard(devicesViewModel)
        HaCard(title = "Camera feeds") {
            CardText("Where new camera feeds go in Home Assistant. Each camera can override it from its device sheet.")
            Column(Modifier.padding(horizontal = 12.dp)) {
                StreamDestination.entries.forEach { destination ->
                    ChoiceRow(destinationLabel(destination), state.settings.feedDestination == destination.storedName) {
                        viewModel.selectDefaultDestination(destination)
                    }
                }
            }
            if (state.settings.feedDestination == StreamDestination.GO2RTC.storedName) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Go2rtcFields(state, viewModel) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                CardAction("Save", viewModel::saveFeedSettings)
            }
        }
    }
}
