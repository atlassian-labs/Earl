package io.atlassian.earl.styles

import javafx.scene.paint.Color
import tornadofx.*

class Styles : Stylesheet() {

    init {
        select("*") {
            fontFamily = "sans-serif"

            defaultColor0 {
                backgroundColor = MultiValue(arrayOf(Color.BLUE))
                stroke = Color.BLUE
            }

            defaultColor1 {
                backgroundColor = MultiValue(arrayOf(Color.ORANGE))
                stroke = Color.ORANGE
            }
        }

        chartSeriesLine {
            strokeWidth = Dimension(1.0, Dimension.LinearUnits.px)
        }
    }
}

