package app.televault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.televault.domain.AuthProtocol
import app.televault.telegram.CountryDialingInfo
import app.televault.telegram.TelegramGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PhoneCountries(val countries: List<CountryDialingInfo> = emptyList(), val suggestedIso: String = "", val loading: Boolean = false)

class AuthViewModel(private val gateway: TelegramGateway) : ViewModel() {
    val authorization = gateway.authorization
    private val phoneMutable = MutableStateFlow(PhoneCountries())
    val phoneCountries = phoneMutable.asStateFlow()
    fun preparePhoneCountries() {
        if (phoneMutable.value.loading || phoneMutable.value.countries.isNotEmpty()) return
        phoneMutable.value = phoneMutable.value.copy(loading = true)
        viewModelScope.launch {
            try {
                val countries = gateway.phoneCountries()
                phoneMutable.value = PhoneCountries(countries, loading = true)
                val suggested = gateway.suggestedPhoneCountry()
                phoneMutable.value = PhoneCountries(countries, suggested)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                phoneMutable.value = phoneMutable.value.copy(loading = false)
            }
        }
    }
    fun reconnect() {
        viewModelScope.launch {
            try {
                gateway.reconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
    }
    fun submit(action: AuthProtocol.Action, value: String = "", lastName: String = "") {
        viewModelScope.launch {
            try {
                gateway.submit(action, value, lastName)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                reconnect()
            }
        }
    }

    class Factory(private val gateway: TelegramGateway) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AuthViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return AuthViewModel(gateway) as T
        }
    }
}
