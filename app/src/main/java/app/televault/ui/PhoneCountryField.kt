package app.televault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.televault.PhoneCountries
import app.televault.domain.PhoneNumberInput
import app.televault.telegram.CountryDialingInfo
import java.util.Locale

private data class DialChoice(val iso: String, val name: String, val flag: String, val code: String)
private val commonCountries = listOf(
    CountryDialingInfo("IN", "India", "🇮🇳", listOf("91")), CountryDialingInfo("BD", "Bangladesh", "🇧🇩", listOf("880")),
    CountryDialingInfo("US", "United States", "🇺🇸", listOf("1")), CountryDialingInfo("GB", "United Kingdom", "🇬🇧", listOf("44")),
    CountryDialingInfo("CA", "Canada", "🇨🇦", listOf("1")), CountryDialingInfo("AU", "Australia", "🇦🇺", listOf("61")),
    CountryDialingInfo("AE", "United Arab Emirates", "🇦🇪", listOf("971")), CountryDialingInfo("NP", "Nepal", "🇳🇵", listOf("977")),
    CountryDialingInfo("PK", "Pakistan", "🇵🇰", listOf("92")), CountryDialingInfo("SG", "Singapore", "🇸🇬", listOf("65")),
    CountryDialingInfo("DE", "Germany", "🇩🇪", listOf("49")), CountryDialingInfo("FR", "France", "🇫🇷", listOf("33")),
    CountryDialingInfo("JP", "Japan", "🇯🇵", listOf("81")), CountryDialingInfo("CN", "China", "🇨🇳", listOf("86")),
    CountryDialingInfo("ZA", "South Africa", "🇿🇦", listOf("27")), CountryDialingInfo("BR", "Brazil", "🇧🇷", listOf("55"))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneCountryField(state: PhoneCountries, enabled: Boolean, retry: () -> Unit, changed: (String) -> Unit) {
    val choices = remember(state.countries) { (state.countries.ifEmpty { commonCountries }).flatMap { country -> country.codes.map { DialChoice(country.iso, country.name, country.flag, it) } }.sortedBy { it.name } }
    var selected by remember { mutableStateOf(choices.firstOrNull { it.iso == Locale.getDefault().country } ?: choices.firstOrNull { it.iso == "IN" } ?: choices.first()) }
    var edited by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    var manualInternational by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(state.suggestedIso, choices) {
        if (!edited && phone.isEmpty()) choices.firstOrNull { it.iso == state.suggestedIso }?.let { selected = it }
    }
    LaunchedEffect(phone, selected, manualInternational) {
        changed(if (manualInternational) phone.takeIf { it.matches(Regex("\\+[0-9]{1,15}")) }.orEmpty() else PhoneNumberInput.international(selected.code, phone))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({
            picker = true
            query = ""
        }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(selected.flag + " " + selected.name + "  +" + selected.code, Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, label("Select country code", "দেশের কোড বাছুন"))
        }
        OutlinedTextField(phone, { value ->
            edited = true
            val split = PhoneNumberInput.split(value, selected.code, choices.map { it.code })
            if (split.international() && split.code().isNotEmpty()) {
                if (selected.code != split.code()) choices.firstOrNull { it.code == split.code() }?.let { selected = it }
                phone = split.national().take(15 - split.code().length)
                manualInternational = false
            } else if (split.international()) {
                phone = "+" + split.national().take(15)
                manualInternational = true
            } else {
                phone = split.national().take(15 - selected.code.length)
                manualInternational = false
            }
        }, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, label = { Text(label("Phone number", "ফোন নম্বর")) }, prefix = { if (!manualInternational) Text("+${selected.code} ") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        Text(label("Choose a country, or paste a full +country number. Suggestions use Telegram's IP estimate and can be changed.", "দেশ বাছুন অথবা +কোডসহ পুরো নম্বর পেস্ট করুন। Telegram-এর IP অনুযায়ী সাজেশন বদলাতে পারবেন।"), style = MaterialTheme.typography.bodySmall)
        if (state.loading) Text(label("Loading country list…", "দেশের তালিকা আসছে…"), style = MaterialTheme.typography.labelSmall)
        if (state.countries.isEmpty() && !state.loading) {
            Text(label("Common codes shown offline; a full international number can always be entered.", "অফলাইনে সাধারণ কোড দেখানো হয়েছে; +সহ পুরো আন্তর্জাতিক নম্বরও লিখতে পারেন।"), style = MaterialTheme.typography.labelSmall)
            TextButton(retry) { Text(label("Load full country list", "সব দেশের তালিকা আনুন")) }
        }
    }
    if (picker) {
        ModalBottomSheet(onDismissRequest = { picker = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(max = 520.dp)) {
                Text(label("Country code", "দেশের কোড"), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 12.dp), singleLine = true, label = { Text(label("Search country / code", "দেশ / কোড খুঁজুন")) })
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(choices.filter { it.name.contains(query, true) || it.iso.contains(query, true) || ("+" + it.code).contains(query) }, key = { "${it.iso}:${it.code}" }) { choice ->
                        ListItem(
                            headlineContent = { Text(choice.flag + " " + choice.name) },
                            trailingContent = { Text("+" + choice.code) },
                            modifier = Modifier.clickable {
                                edited = true
                                selected = choice
                                manualInternational = false
                                picker = false
                            }
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
