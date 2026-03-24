package com.example.bikeracer

class Obstacle(
    var x: Float,
    var y: Float,
    var width: Int,
    var height: Int,
    var speed: Float
) {
    fun update(currentSpeed: Float, factor: Float = 0.6f) {
        y += speed + (currentSpeed * factor)
    }
}
