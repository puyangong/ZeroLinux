package com.zerolinux.app.core

import org.json.JSONObject
import java.io.File

/** 原子写 JSON：先写 .tmp 再 rename，避免多进程读到半个文件。 */
object JsonStore {

    fun read(file: File): JSONObject = runCatching {
        if (file.exists() && file.length() > 0) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject()
    }.getOrElse { JSONObject() }

    fun write(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString(2), Charsets.UTF_8)
        if (file.exists() && !file.delete()) {
            // 极少数情况下删除失败，退化为直接覆盖
            file.writeText(json.toString(2), Charsets.UTF_8)
            runCatching { tmp.delete() }
            return
        }
        if (!tmp.renameTo(file)) {
            file.writeText(json.toString(2), Charsets.UTF_8)
            runCatching { tmp.delete() }
        }
    }
}
