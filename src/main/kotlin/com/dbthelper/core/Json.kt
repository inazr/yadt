package com.dbthelper.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule

/** Shared Jackson mapper; ObjectMapper is thread-safe once configured. */
val jsonMapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
