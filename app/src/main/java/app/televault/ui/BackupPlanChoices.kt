package app.televault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun BackupPlanChoices(unlimited: Boolean, choose: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label("How would you like to back up?", "কীভাবে ব্যাকআপ করবেন?"), style = MaterialTheme.typography.titleMedium)
        listOf(false, true).forEach { full ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(unlimited == full, onClick = { choose(full) }, role = Role.RadioButton), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(unlimited == full, null)
                Text(if (full) label("Full backup · I have unlimited internet", "Full backup · আমার আনলিমিটেড নেট আছে") else label("Limited data · 250 MB/day by default; photos first, Camera videos up to 2 min on mobile data", "সীমিত ডেটা · ডিফল্ট দৈনিক ২৫০ MB; আগে ছবি, মোবাইল ডেটায় ২ মিনিট পর্যন্ত ক্যামেরা ভিডিও"), Modifier.padding(8.dp))
            }
        }
        Text(label("Full removes daily mobile-data and Camera video-length limits. Wi-Fi keeps full-length backup in either mode. Your saved custom limits stay in Settings.", "Full-এ দৈনিক মোবাইল ডেটা ও ক্যামেরা ভিডিওর দৈর্ঘ্যের সীমা নেই। দুই মোডেই Wi-Fi-তে পুরো দৈর্ঘ্যের ভিডিও ব্যাকআপ হবে। নিজের সেভ করা সীমা Settings-এ থাকবে।"), style = MaterialTheme.typography.bodySmall)
    }
}
