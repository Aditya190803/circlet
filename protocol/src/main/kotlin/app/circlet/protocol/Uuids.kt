// Ported from OpenStrap protocol/lib/src/band.dart — GattProfile.gen4.
package app.circlet.protocol

import java.util.UUID

/** GATT identifiers for a WHOOP 4.0. No other generation is addressed here. */
object Uuids {
    val SERVICE: UUID = UUID.fromString("61080001-8d6d-82b8-614a-1c8cb0f8dcc6")
    val CMD_TO_STRAP: UUID = UUID.fromString("61080002-8d6d-82b8-614a-1c8cb0f8dcc6")
    val CMD_FROM_STRAP: UUID = UUID.fromString("61080003-8d6d-82b8-614a-1c8cb0f8dcc6")
    val EVENTS_FROM_STRAP: UUID = UUID.fromString("61080004-8d6d-82b8-614a-1c8cb0f8dcc6")
    val DATA_FROM_STRAP: UUID = UUID.fromString("61080005-8d6d-82b8-614a-1c8cb0f8dcc6")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
