package com.example.meshovik_prototype

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform