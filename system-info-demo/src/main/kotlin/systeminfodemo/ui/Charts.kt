@file:Suppress("MagicNumber", "MaxLineLength")

package systeminfodemo.ui

import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import io.github.koalaplot.core.animation.StartAnimationUseCase
import io.github.koalaplot.core.bar.VerticalBarPlot
import io.github.koalaplot.core.bar.verticalSolidBar
import io.github.koalaplot.core.line.LinePlot2
import io.github.koalaplot.core.style.LineStyle
import io.github.koalaplot.core.xygraph.AxisContent
import io.github.koalaplot.core.xygraph.DefaultPoint
import io.github.koalaplot.core.xygraph.FloatLinearAxisModel
import io.github.koalaplot.core.xygraph.XYGraph
import io.github.koalaplot.core.xygraph.rememberAxisStyle

private fun emptyAxisContent(): AxisContent<Float> =
    AxisContent(
        labels = {},
        title = {},
        style =
            io.github.koalaplot.core.xygraph.AxisStyle(
                lineWidth = 0.dp,
                majorTickSize = 0.dp,
                minorTickSize = 0.dp,
            ),
    )

@Composable
fun LineChart(
    data: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    if (data.size < 2) return
    val points = data.mapIndexed { i, v -> DefaultPoint(i.toFloat(), v) }
    val yStyle = rememberAxisStyle()
    XYGraph(
        xAxisModel = FloatLinearAxisModel(0f..(data.size - 1).toFloat().coerceAtLeast(1f)),
        yAxisModel = FloatLinearAxisModel(0f..100f),
        xAxisContent = emptyAxisContent(),
        yAxisContent =
            AxisContent(labels = {
                org.jetbrains.jewel.ui.component
                    .Text("%.0f".format(it))
            }, title = {}, style = yStyle),
        modifier = modifier.fillMaxWidth().height(200.dp),
    ) {
        LinePlot2(
            data = points,
            lineStyle = LineStyle(brush = SolidColor(lineColor), strokeWidth = 2.dp),
            animationSpec = snap(),
        )
    }
}

@Composable
fun BarChart(
    xData: List<Float>,
    yData: List<Float>,
    barColor: Color,
    maxY: Float = 100f,
    modifier: Modifier = Modifier,
) {
    if (xData.isEmpty()) return
    val yStyle = rememberAxisStyle()
    XYGraph(
        xAxisModel = FloatLinearAxisModel(-0.5f..(xData.size - 0.5f)),
        yAxisModel = FloatLinearAxisModel(0f..maxY),
        xAxisContent = emptyAxisContent(),
        yAxisContent =
            AxisContent(labels = {
                org.jetbrains.jewel.ui.component
                    .Text("%.0f".format(it))
            }, title = {}, style = yStyle),
        modifier = modifier.fillMaxWidth().height(200.dp),
    ) {
        VerticalBarPlot(
            xData = xData,
            yData = yData,
            bar = verticalSolidBar(barColor),
            startAnimationUseCase =
                StartAnimationUseCase(
                    StartAnimationUseCase.ExecutionType.None,
                    snap(),
                ),
        )
    }
}
