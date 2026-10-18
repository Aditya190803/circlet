package app.circlet.strap.link

import java.util.UUID

internal enum class GattOperation { MTU, DISCOVER, DESCRIPTOR, WRITE }

internal class GattCallbackKey(val connection: Any, val operation: GattOperation, val uuid: UUID? = null) {
    fun matches(connection: Any, operation: GattOperation, uuid: UUID?): Boolean =
        this.connection === connection && this.operation == operation && this.uuid == uuid
}
