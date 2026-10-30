package me.eternal.purrfect.core.ui.menu

import android.view.View
import android.view.ViewGroup
import me.eternal.purrfect.core.ModContext
import me.eternal.purrfect.core.event.events.impl.AddViewEvent

abstract class AbstractMenu {
    lateinit var menuViewInjector: MenuViewInjector
    lateinit var context: ModContext

    open fun inject(parent: ViewGroup, view: View, viewConsumer: (View) -> Unit) {}
    open fun onViewAdded(event: AddViewEvent) {}

    open fun init() {}
}