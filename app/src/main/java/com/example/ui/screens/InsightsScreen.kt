package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.BentoCard
import com.example.ui.components.BentoActionCard
import com.example.ui.components.RetroMetricTile
import com.example.ui.components.RetroSectionHeader
import com.example.ui.theme.CardBlack
import com.example.ui.theme.CardWhite
import com.example.ui.theme.TealAccent
import com.example.ui.viewmodel.PomodoroViewModel
import java.text.SimpleDateFormat
import java.util.*
import com.example.domain.InsightsCalculator

@Composable
fun InsightsScreen(
    viewModel: PomodoroViewModel,
    modifier: Modifier = Modifier
) {
    val sessionLogs by viewModel.sessionLogs.collectAsState()
    val panicLogs by viewModel.panicLogs.collectAsState()

    val metrics = InsightsCalculator.calculate(sessionLogs, panicLogs)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))

            RetroSectionHeader("Focus metrics")
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RetroMetricTile("Focus minutes", metrics.focusMinutes.toString(), Modifier.weight(1f))
                RetroMetricTile("Completion", "${metrics.completionRatePercent}%", Modifier.weight(1f))
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RetroMetricTile("Resilience", metrics.resilienceScore.toString(), Modifier.weight(1f))
                RetroMetricTile("Fast recovery", metrics.rapidRecoveries.toString(), Modifier.weight(1f))
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Recent Sessions & Tasks",
                style = MaterialTheme.typography.titleMedium,
                color = CardBlack,
                modifier = Modifier.fillMaxWidth()
            )
        }

        items(sessionLogs.sortedByDescending { it.timestamp }.take(20)) { log ->
            BentoCard(
                modifier = Modifier.fillMaxWidth(),
                color = CardBlack,
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = if (log.status == "COMPLETED") Icons.Default.Assignment else Icons.Default.Bolt
                    val tint = if (log.status == "COMPLETED") TealAccent else MaterialTheme.colorScheme.error

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(CardWhite.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = icon, contentDescription = null, tint = tint)
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = log.taskName?.takeIf { it.isNotBlank() } ?: "Deep Focus",
                            color = CardWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date(log.timestamp)),
                            color = CardWhite.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }

                    Text(
                        text = "${log.durationSeconds / 60}m",
                        color = CardWhite,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}
