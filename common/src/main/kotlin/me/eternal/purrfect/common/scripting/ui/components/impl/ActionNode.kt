package me.eternal.purrfect.common.scripting.ui.components.impl

import me.eternal.purrfect.common.scripting.ui.components.Node
import me.eternal.purrfect.common.scripting.ui.components.NodeType

enum class ActionType {
    LAUNCHED,
    DISPOSE
}

class ActionNode(
    val actionType: ActionType,
    val key: Any = Unit,
    val callback: () -> Unit
): Node(NodeType.ACTION)