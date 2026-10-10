package app.circlet.ui.strap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.circlet.core.CoreError
import app.circlet.core.FoundStrap
import app.circlet.core.StrapError
import app.circlet.ui.design.Glyph
import app.circlet.ui.design.Icon
import app.circlet.ui.design.Palette
import app.circlet.ui.design.PrimaryButton
import app.circlet.ui.design.SecondaryButton
import app.circlet.ui.design.Shapes
import app.circlet.ui.design.Sweep
import app.circlet.ui.design.body
import app.circlet.ui.design.label
import app.circlet.ui.design.title
import app.circlet.ui.design.words
import app.circlet.ui.state.PairingPhase

private fun distance(rssiDbm: Int) = if (rssiDbm > -65) "VERY CLOSE" else if (rssiDbm > -80) "NEARBY" else "FAR"

/** What the user can do about a pairing failure, when it is something other than trying again. */
enum class PairingFix { ALLOW_NEARBY, BLUETOOTH_SETTINGS }

private fun CoreError.fix(): PairingFix? = when ((this as? CoreError.Strap)?.error) {
    StrapError.PermissionMissing -> PairingFix.ALLOW_NEARBY
    StrapError.BluetoothOff, StrapError.BondFailed -> PairingFix.BLUETOOTH_SETTINGS
    else -> null
}

/**
 * The live scan list and everything around it: searching, nothing found, connecting, and a
 * typed failure with the step that fixes it.
 */
@Composable
fun StrapFinder(
    phase: PairingPhase,
    scanning: Boolean,
    found: List<FoundStrap>,
    onScan: () -> Unit,
    onPair: (FoundStrap) -> Unit,
    onFix: (PairingFix) -> Unit,
) {
    val connecting = phase.connecting
    if (connecting != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyph.Strap, Palette.White, 24.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(connecting.name, style = title(17.sp))
                Text("Connecting. Keep the strap close.", style = body(13.sp, Palette.Faint))
            }
        }
        Spacer(Modifier.height(14.dp))
        Sweep(Palette.Teal)
        return
    }
    found.forEach { item ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(Shapes.control)
                .background(Palette.CardRaised)
                .clickable(role = Role.Button, onClickLabel = "Pair ${item.name}") { onPair(item) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Glyph.Strap, Palette.White, 22.dp)
            Spacer(Modifier.width(12.dp))
            Text(item.name, style = title(17.sp), modifier = Modifier.weight(1f))
            Text(distance(item.rssiDbm), style = label(11.sp, Palette.Faint))
        }
        Spacer(Modifier.height(8.dp))
    }
    val error = phase.error
    if (error != null) {
        Row {
            Icon(Glyph.Warning, Palette.Warn, 18.dp, Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(10.dp))
            Text(error.words(), style = body(13.sp, Palette.Warn))
        }
        Spacer(Modifier.height(12.dp))
        when (val fix = error.fix()) {
            PairingFix.ALLOW_NEARBY -> SecondaryButton("ALLOW NEARBY DEVICES") { onFix(fix) }
            PairingFix.BLUETOOTH_SETTINGS -> SecondaryButton("OPEN BLUETOOTH SETTINGS") { onFix(fix) }
            null -> Unit
        }
        if (error.fix() != null) Spacer(Modifier.height(8.dp))
    }
    if (scanning) {
        Text(if (found.isEmpty()) "Searching for straps nearby" else "Still searching", style = body(13.sp, Palette.Faint))
        Spacer(Modifier.height(10.dp))
        Sweep(Palette.Sleep)
    } else {
        if (found.isEmpty() && error == null) {
            Text("No strap found yet. Bring it close and make sure it is not connected to another phone.", style = body(13.sp, Palette.Faint))
            Spacer(Modifier.height(12.dp))
        }
        PrimaryButton(if (found.isEmpty()) "SEARCH FOR STRAP" else "SEARCH AGAIN", onClick = onScan)
    }
}
