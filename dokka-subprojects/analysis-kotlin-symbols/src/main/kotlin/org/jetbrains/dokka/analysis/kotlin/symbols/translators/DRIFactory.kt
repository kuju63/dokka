/*
 * Copyright 2014-2024 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.dokka.analysis.kotlin.symbols.translators

import org.jetbrains.dokka.links.*
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaSymbolKind
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaSymbolWithKind
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaSymbolWithTypeParameters
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

internal fun ClassId.createDRI(): DRI = DRI(
    packageName = this.packageFqName.asString(), classNames = this.relativeClassName.asString()
)

private fun CallableId.createDRI(receiver: TypeReference?, params: List<TypeReference>): DRI = DRI(
    packageName = this.packageName.asString(),
    classNames = this.className?.asString(),
    callable = Callable(
        this.callableName.asString(),
        params = params,
        receiver = receiver
    )
)

internal fun getDRIFromNonErrorClassType(nonErrorClassType: KaNonErrorClassType): DRI =
    nonErrorClassType.classId.createDRI()

private val KaCallableSymbol.callableId
    get() = this.callableIdIfNonLocal ?: throw IllegalStateException("Can not get callable Id due to it is local")

// because of compatibility with Dokka K1, DRI of entry is kept as non-callable
internal fun getDRIFromEnumEntry(symbol: KaEnumEntrySymbol): DRI =
    symbol.callableId.let {
        DRI(
            packageName = it.packageName.asString(),
            classNames = it.className?.asString() + "." + it.callableName.asString(),
        )
    }.withEnumEntryExtra()


internal fun KaSession.getDRIFromTypeParameter(symbol: KaTypeParameterSymbol): DRI {
    val containingSymbol =
        (symbol.getContainingSymbol() as? KaSymbolWithTypeParameters)
            ?: throw IllegalStateException("Containing symbol is null for type parameter")
    val typeParameters = containingSymbol.typeParameters
    val index = typeParameters.indexOfFirst { symbol.name == it.name }
    return getDRIFromSymbol(containingSymbol).copy(target = PointingToGenericParameters(index))
}

internal fun KaSession.getDRIFromConstructor(symbol: KaConstructorSymbol): DRI =
    (symbol.containingClassIdIfNonLocal
        ?: throw IllegalStateException("Can not get class Id due to it is local")).createDRI().copy(
        callable = Callable(
            name = symbol.containingClassIdIfNonLocal?.shortClassName?.asString() ?: "",
            params = symbol.valueParameters.map { getTypeReferenceFrom(it.returnType) })
    )

internal fun KaSession.getDRIFromVariableLike(symbol: KaVariableLikeSymbol): DRI {
    val receiver = symbol.receiverType?.let {
        getTypeReferenceFrom(it)
    }
    return symbol.callableId.createDRI(receiver, emptyList())
}

internal fun KaSession.getDRIFromFunctionLike(symbol: KaFunctionLikeSymbol): DRI {
    val params = symbol.valueParameters.map { getTypeReferenceFrom(it.returnType) }
    val receiver = symbol.receiverType?.let {
        getTypeReferenceFrom(it)
    }
    return symbol.callableIdIfNonLocal?.createDRI(receiver, params)
        ?: getDRIFromLocalFunction(symbol)
}

internal fun getDRIFromClassLike(symbol: KaClassLikeSymbol): DRI =
    symbol.classIdIfNonLocal?.createDRI() ?: throw IllegalStateException("Can not get class Id due to it is local")

internal fun getDRIFromPackage(symbol: KaPackageSymbol): DRI =
    DRI(packageName = symbol.fqName.asString())

internal fun KaSession.getDRIFromValueParameter(symbol: KaValueParameterSymbol): DRI {
    val function = (symbol.getContainingSymbol() as? KaFunctionLikeSymbol)
        ?: throw IllegalStateException("Containing symbol is null for type parameter")
    val index = function.valueParameters.indexOfFirst { it.name == symbol.name }
    val funDRI = getDRIFromFunctionLike(function)
    return funDRI.copy(target = PointingToCallableParameters(index))
}

/**
 * @return [DRI] to receiver type
 */
internal fun KaSession.getDRIFromReceiverParameter(receiverParameterSymbol: KaReceiverParameterSymbol): DRI =
    getDRIFromReceiverType(receiverParameterSymbol.type)

private fun KaSession.getDRIFromReceiverType(type: KaType): DRI {
    return when(type) {
        is KaNonErrorClassType -> getDRIFromNonErrorClassType(type)
        is KaTypeParameterType -> getDRIFromTypeParameter(type.symbol)
        is KaDefinitelyNotNullType -> getDRIFromReceiverType(type.original)
        is KaTypeErrorType -> DRI(packageName = "", classNames = "$ERROR_CLASS_NAME $type")
        is KaClassErrorType -> DRI(packageName = "", classNames = "$ERROR_CLASS_NAME $type")
        is KaDynamicType -> DRI(packageName = "", classNames = "$ERROR_CLASS_NAME $type") // prohibited by a compiler, but it's a possible input

        is KaCapturedType -> throw IllegalStateException("Unexpected non-denotable type while creating DRI $type")
        is KaFlexibleType -> throw IllegalStateException("Unexpected non-denotable type while creating DRI $type")
        is KaIntegerLiteralType -> throw IllegalStateException("Unexpected non-denotable type while creating DRI $type")
        is KaIntersectionType -> throw IllegalStateException("Unexpected non-denotable type while creating DRI $type")
    }
}

internal fun KaSession.getDRIFromSymbol(symbol: KaSymbol): DRI =
    when (symbol) {
        is KaEnumEntrySymbol -> getDRIFromEnumEntry(symbol)
        is KaTypeParameterSymbol -> getDRIFromTypeParameter(symbol)
        is KaConstructorSymbol -> getDRIFromConstructor(symbol)
        is KaValueParameterSymbol -> getDRIFromValueParameter(symbol)
        is KaVariableLikeSymbol -> getDRIFromVariableLike(symbol)
        is KaFunctionLikeSymbol -> getDRIFromFunctionLike(symbol)
        is KaClassLikeSymbol -> getDRIFromClassLike(symbol)
        is KaPackageSymbol -> getDRIFromPackage(symbol)
        is KaReceiverParameterSymbol -> getDRIFromReceiverParameter(symbol)
        else -> throw IllegalStateException("Unknown symbol while creating DRI $symbol")
    }

private fun KaSession.getDRIFromNonCallablePossibleLocalSymbol(symbol: KaSymbol): DRI {
    if ((symbol as? KaSymbolWithKind)?.symbolKind == KaSymbolKind.LOCAL) {
        return symbol.getContainingSymbol()?.let { getDRIFromNonCallablePossibleLocalSymbol(it) }
            ?: throw IllegalStateException("Can't get containing symbol for local symbol")
    }
    return getDRIFromSymbol(symbol)
}

/**
 * Currently, it's used only for functions from enum entry,
 * For its members: `memberSymbol.callableIdIfNonLocal=null`
 */
private fun KaSession.getDRIFromLocalFunction(symbol: KaFunctionLikeSymbol): DRI {
    /**
     * A function is inside local object
     */
    val containingSymbolDRI = symbol.getContainingSymbol()?.let { getDRIFromNonCallablePossibleLocalSymbol(it) }
        ?: throw IllegalStateException("Can't get containing symbol for local function")
    return containingSymbolDRI.copy(
        callable = Callable(
            (symbol as? KaNamedSymbol)?.name?.asString() ?: "",
            params = symbol.valueParameters.map { getTypeReferenceFrom(it.returnType) },
            receiver = symbol.receiverType?.let {
                getTypeReferenceFrom(it)
            }
        )
    )
}

// ----------- DRI => compiler identifiers ----------------------------------------------------------------------------
internal fun getClassIdFromDRI(dri: DRI) = ClassId(
    FqName(dri.packageName ?: ""),
    FqName(dri.classNames ?: throw IllegalStateException("DRI must have `classNames` to get ClassID")),
    false
)

