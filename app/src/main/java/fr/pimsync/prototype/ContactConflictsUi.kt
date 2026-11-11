package fr.pimsync.prototype

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import fr.pimsync.engine.ComparableVersion
import fr.pimsync.engine.ConflictComparison
import fr.pimsync.engine.ObjectState
import fr.pimsync.engine.Operation
import fr.pimsync.engine.OperationKind
import fr.pimsync.engine.PimSyncEngine
import java.text.DateFormat
import java.util.Date

/** Lists contact conflicts found in the PIMSync journal and lets the user choose which version to keep. */
class ContactConflictsUi(private val activity: Activity) {
    private data class Conflict(val objectId: String, val versions: List<Operation>)

    private val preferences = activity.getSharedPreferences("pimsync-prototype", Context.MODE_PRIVATE)

    fun show() {
        toast("Lecture du journal PIMSync…")
        withEngine({ engine -> loadConflicts(engine) }) { conflicts ->
            if (conflicts.isEmpty()) {
                AlertDialog.Builder(activity)
                    .setTitle("Conflits de contacts")
                    .setMessage("Aucun conflit à résoudre dans le journal PIMSync.")
                    .setPositiveButton("Fermer", null)
                    .show()
            } else {
                showList(conflicts)
            }
        }
    }

    private fun loadConflicts(engine: PimSyncEngine): List<Conflict> =
        engine.inspectAll()
            .filter { it.objectId.startsWith(PimSyncContactsSyncAdapter.TEST_CONTACT_PREFIX) }
            .mapNotNull { inspection ->
                (inspection.state as? ObjectState.Conflict)?.let { Conflict(inspection.objectId, it.concurrentOperations) }
            }

    private fun showList(conflicts: List<Conflict>) {
        AlertDialog.Builder(activity)
            .setTitle("Conflits de contacts (${conflicts.size})")
            .setItems(conflicts.map(::title).toTypedArray()) { _, which -> showComparison(conflicts[which]) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun showComparison(conflict: Conflict) {
        val text = TextView(activity).apply {
            this.text = describe(conflict)
            textSize = 15f
            setPadding(48, 24, 48, 24)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(activity)
            .setTitle(title(conflict))
            .setView(ScrollView(activity).apply { addView(text) })
            .setPositiveButton("Choisir une version…") { _, _ -> showChoice(conflict) }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    private fun showChoice(conflict: Conflict) {
        val options = conflict.versions.map {
            if (it.kind == OperationKind.DELETE) "Supprimer le contact (${label(it)})" else "Garder la version de ${label(it)}"
        }
        AlertDialog.Builder(activity)
            .setTitle("Quelle version conserver ?")
            .setItems(options.toTypedArray()) { _, which -> confirm(conflict, conflict.versions[which]) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun confirm(conflict: Conflict, chosen: Operation) {
        val deleting = chosen.kind == OperationKind.DELETE
        val message = if (deleting) {
            "Le contact sera supprimé sur tous les téléphones après synchronisation. " +
                "Les anciennes versions restent dans le journal PIMSync."
        } else {
            "La version de ${label(chosen)} deviendra la fiche commune. L’autre version sera remplacée sur les " +
                "téléphones après synchronisation, mais elle reste dans le journal PIMSync."
        }
        AlertDialog.Builder(activity)
            .setTitle(if (deleting) "Supprimer ce contact ?" else "Confirmer ce choix ?")
            .setMessage(message)
            .setPositiveButton(if (deleting) "Supprimer" else "Confirmer") { _, _ -> resolve(conflict, chosen) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun resolve(conflict: Conflict, chosen: Operation) {
        withEngine({ engine ->
            // The journal may have changed since the list was shown; never resolve a conflict the user has not seen.
            val state = engine.inspect(conflict.objectId).state
            val currentIds = (state as? ObjectState.Conflict)?.concurrentOperations?.map { it.operationId }?.toSet()
            check(currentIds == conflict.versions.map { it.operationId }.toSet()) {
                "Le conflit a changé depuis l’affichage. Rouvre la liste des conflits."
            }
            // upsert/delete join the whole history, so the new operation supersedes every competing version.
            if (chosen.kind == OperationKind.DELETE) {
                engine.delete(conflict.objectId)
            } else {
                engine.upsert(conflict.objectId, chosen.payload.orEmpty())
            }
            Unit
        }) {
            if (preferences.getBoolean(PimSyncAccount.PREFERENCE_ACCOUNT_ENABLED, false)) {
                ContentResolver.requestSync(PimSyncAccount.account(), PimSyncAccount.AUTHORITY, Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                })
            }
            toast("Conflit résolu. Synchronisation demandée ; Syncthing transmettra le choix à l’autre téléphone.")
        }
    }

    private fun describe(conflict: Conflict): String = buildString {
        appendLine(
            "${conflict.versions.size} versions de cette fiche ont été modifiées en parallèle. " +
                "Elles sont toutes conservées dans le journal : rien n’est écrasé tant que tu n’as pas choisi."
        )
        appendLine()
        conflict.versions.filter { it.kind == OperationKind.DELETE }.forEach {
            appendLine("• ${label(it)} a SUPPRIMÉ ce contact.")
        }
        val rows = ConflictComparison.compare(conflict.versions.map { operation ->
            ComparableVersion(label(operation), fieldsOf(operation))
        })
        val different = rows.count { it.differs }
        if (rows.isNotEmpty()) {
            appendLine()
            appendLine(if (different == 0) "Les versions ont le même contenu." else "$different champ(s) différent(s), marqués ≠ :")
            appendLine()
        }
        rows.forEach { row ->
            if (row.differs) {
                appendLine("≠ ${row.field}")
                row.values.forEach { (who, value) -> appendLine("    $who : ${value.ifBlank { "(vide)" }}") }
            } else {
                val value = row.values.first().second
                if (value.isNotBlank()) appendLine("= ${row.field} : $value")
            }
        }
    }

    private fun fieldsOf(operation: Operation): Map<String, String>? {
        if (operation.kind == OperationKind.DELETE) return null
        val card = runCatching { VCardCodec.decode(operation.payload.orEmpty()) }.getOrNull()
        return if (card != null) ContactCardFields.of(card) else mapOf("Contenu brut" to operation.payload.orEmpty())
    }

    private fun title(conflict: Conflict): String =
        conflict.versions.firstNotNullOfOrNull { operation ->
            fieldsOf(operation)?.get("Nom")?.takeIf(String::isNotBlank)
        } ?: "Contact sans nom"

    private fun label(operation: Operation): String {
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(operation.createdAtMillis))
        return "${operation.deviceId} ($date)"
    }

    private fun openEngine(): PimSyncEngine {
        val treeUri = preferences.getString("tree_uri", null)?.let(Uri::parse)
            ?: error("Choisissez d’abord le dossier PIMSync partagé dans l’application.")
        val root = DocumentFile.fromTreeUri(activity, treeUri)?.takeIf { it.canRead() && it.canWrite() }
            ?: error("Le dossier PIMSync partagé n’est pas accessible.")
        val deviceId = preferences.getString("device_id", "telephone-a") ?: "telephone-a"
        return PimSyncEngine(SafOperationStore(activity.contentResolver, root, deviceId), deviceId)
    }

    private fun <T> withEngine(work: (PimSyncEngine) -> T, done: (T) -> Unit) {
        Thread({
            val outcome = runCatching { work(openEngine()) }
            activity.runOnUiThread {
                outcome
                    .onSuccess(done)
                    .onFailure { toast("Impossible : ${it.message ?: it.javaClass.simpleName}") }
            }
        }, "pimsync-conflicts").start()
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
