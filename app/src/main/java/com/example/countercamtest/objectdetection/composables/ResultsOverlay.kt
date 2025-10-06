/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.countercamtest.objectdetection.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectionResult
import android.util.Log

// This composable is used to display the results of the object detection

// Beside results, it also needs to know the dimensions of the media (video, image, etc.)
// on which the object detection was performed so that it can draw the results properly.

// This information is needed because each result bounds are calculated based on the
// dimensions of the original media that the object detection was performed on. But for
// us to draw the bounds correctly, we need to draw the bounds based on the dimensions of
// the UI space that the media is being displayed in.

// An important note is that this composable should have the exact same UI dimensions of the
// media being displayed, and it should be placed exactly on the top of the displayed media.
// For example, if an image is being displayed in a Box composable, the overlay should be placed
// on top of the image and it should fill the Box composable.
// This is a must because it scales the result bounds according to the provided frame dimensions
// as well as the max available UI width and height

val Turquoise = Color(0xFF40E0D0)

@Composable
fun ResultsOverlay(
    results: ObjectDetectionResult,
    frameWidth: Int,
    frameHeight: Int,
) {
    val detections = results.detections()
    //Log.d("ResultsOverlay", "🎨 Rendering overlay with ${detections?.size ?: 0} detections, frame: ${frameWidth}x${frameHeight}")

    if (detections == null || detections.isEmpty()) {
        Log.d("ResultsOverlay", "No detections to render")
        return
    }

    //Log.d("ResultsOverlay", "📐 Starting to draw ${detections.size} bounding boxes")

    // Use Canvas for direct drawing - avoids BoxWithConstraints issues
    Canvas(modifier = Modifier.fillMaxSize()) {
        val screenWidth = size.width
        val screenHeight = size.height

        //Log.d("ResultsOverlay", "Canvas size: ${screenWidth}x${screenHeight}")

        // Scaling factors to draw bounding boxes correctly on the screen
        val scaleX = screenWidth / frameWidth.toFloat()
        val scaleY = screenHeight / frameHeight.toFloat()

        detections.forEachIndexed { index, detection ->
            val resultBounds = detection.boundingBox()

            // Convert bounding box coordinates from image space to screen space
            val left = resultBounds.left * scaleX
            val top = resultBounds.top * scaleY
            val right = resultBounds.right * scaleX
            val bottom = resultBounds.bottom * scaleY

            val boxWidth = right - left
            val boxHeight = bottom - top

            //Log.d("ResultsOverlay", "🔸 Box #$index: normalized=[${resultBounds.left}, ${resultBounds.top}, ${resultBounds.right}, ${resultBounds.bottom}], " +
             //       "screen=[${left}, ${top}, ${right}, ${bottom}], size=${boxWidth}x${boxHeight}")

            if (boxWidth > 0 && boxHeight > 0) {
                // Draw bounding box
                drawRect(
                    color = Turquoise,
                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(boxWidth, boxHeight),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f)
                )

                // Draw label background
                val category = detection.categories().firstOrNull()
                if (category != null) {
                    val label = "${category.categoryName()} ${String.format("%.2f", category.score())}"
                    val textPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 40f
                        isAntiAlias = true
                    }
                    val textBounds = android.graphics.Rect()
                    textPaint.getTextBounds(label, 0, label.length, textBounds)

                    val padding = 8f
                    val labelLeft = left + padding
                    val labelTop = top + padding

                    // Draw black background for text
                    drawRect(
                        color = Color.Black.copy(alpha = 0.7f),
                        topLeft = androidx.compose.ui.geometry.Offset(labelLeft - padding, labelTop - textBounds.height() - padding),
                        size = androidx.compose.ui.geometry.Size(
                            textBounds.width() + padding * 2,
                            textBounds.height() + padding * 2
                        )
                    )

                    // Draw text
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        labelLeft,
                        labelTop,
                        textPaint
                    )

                   // Log.d("ResultsOverlay", "✅ Drew box #$index: $label")
                }
            } else {
                Log.w("ResultsOverlay", "❌ Invalid box dimensions #$index: ${boxWidth}x${boxHeight}")
            }
        }
    }
}