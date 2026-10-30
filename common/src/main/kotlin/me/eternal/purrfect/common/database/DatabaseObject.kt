package me.eternal.purrfect.common.database

import android.database.Cursor

interface DatabaseObject {
    fun write(cursor: Cursor)
}
