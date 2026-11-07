package app.callgate.android.modules.ustabulduk

import org.koin.dsl.module

val ustabuldukModule = module {
    single { UbSiteLink(get(), get()) }
    single { UbCommandPoller(get(), get()) }
}
