package com.flynaru.kfp

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.types.PyType
import com.jetbrains.python.psi.types.PyTypeProviderBase
import com.jetbrains.python.psi.types.TypeEvalContext

/**
 * Kubeflow Pipelines type provider for PyCharm.
 *
 * Treat any call to a Python function decorated with `@dsl.component` as returning a `kfp.dsl.PipelineTask` instance.
 */
class KfpComponentTypeProvider : PyTypeProviderBase() {

    private val logger = logger<KfpComponentTypeProvider>()

    private enum class ComponentType(val ident: String) {
        COMPONENT("component"),
        PIPELINE("pipeline")
    }

    override fun getCallType(
        function: PyFunction,
        callSite: PyCallSiteExpression,
        context: TypeEvalContext
    ): Ref<PyType>? {
        val call = callSite as? PyCallExpression
        val resolvedFunc = call?.callee?.let { resolveFunction(it) } ?: return super.getCallType(function, callSite, context)
        val (decorator, type) = getDecorator(resolvedFunc) ?: return super.getCallType(function, callSite, context)

        val returnType = "kfp.dsl.PipelineTask"
        val pyType = resolveType(resolvedFunc, call, returnType)
        return pyType ?: super.getCallType(function, callSite, context)
    }

    override fun getReferenceType(
        referenceTarget: PsiElement,
        context: TypeEvalContext,
        anchor: PsiElement?
    ): Ref<PyType>? {
        if (anchor == null || referenceTarget !is PyFunction)
            return super.getReferenceType(referenceTarget, context, anchor)

        val pair = getDecorator(referenceTarget)
        var pyType: Ref<PyType>? = null

        if (pair != null) {
            val returnType = "kfp.components.PythonComponent"
            pyType = resolveType(referenceTarget, anchor, returnType)
        }

        return pyType ?: super.getReferenceType(referenceTarget, context, anchor)
    }

    private fun resolveType(component: PsiElement, anchor: PsiElement, returnType: String): Ref<PyType>? {
        val psi = PyPsiFacade.getInstance(component.project)
        val pyClass = psi.createClassByQName(returnType, anchor)
        return if (pyClass != null) {
            Ref.create(psi.createClassType(pyClass, false))
        } else {
            null
        }
    }

    private fun resolveFunction(expr: PyExpression): PyFunction? {
        return when (expr) {
            is PyReferenceExpression -> expr.reference.resolve() as? PyFunction
            is PyQualifiedExpression -> expr.qualifier?.let { resolveFunction(it) }
            else -> null
        }
    }

    private fun getDecorator(function: PyFunction): Pair<PyDecorator, ComponentType>? {
        val dlist = function.decoratorList ?: return null
        for (decorator in dlist.decorators) {
            for (type in ComponentType.entries) {
                if (isKfpComponent(decorator, type)) {
                    return Pair(decorator, type)
                }
            }
        }
        return null
    }

    private fun isKfpComponent(decorator: PyDecorator, type: ComponentType): Boolean {
        val name = decorator.name
        var qName = decorator.qualifiedName

        if (!name.equals(type.ident)) return false
        if (qName == null) return false
        if (qName.firstComponent?.equals("kfp") == true) {
            qName = qName.removeHead(1)
        }
        return qName.matches("dsl", type.ident)
    }

}
