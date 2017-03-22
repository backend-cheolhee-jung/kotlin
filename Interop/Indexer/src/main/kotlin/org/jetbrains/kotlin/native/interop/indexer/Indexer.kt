package org.jetbrains.kotlin.native.interop.indexer

import clang.*
import clang.CXIdxEntityKind.*
import clang.CXTypeKind.*
import kotlinx.cinterop.*

private class StructDeclImpl(spelling: String) : StructDecl(spelling) {
    override var def: StructDefImpl? = null
}

private class StructDefImpl(size: Long, align: Int, decl: StructDecl, hasNaturalLayout: Boolean) :
        StructDef(size, align, decl, hasNaturalLayout) {

    override val fields = mutableListOf<Field>()
}

private class EnumDefImpl(spelling: String, type: PrimitiveType) : EnumDef(spelling, type) {
    override val constants = mutableListOf<EnumConstant>()
}

internal class NativeIndexImpl(val language: Language) : NativeIndex() {

    private data class DeclarationID(val usr: String)

    private val structById = mutableMapOf<DeclarationID, StructDeclImpl>()

    override val structs: List<StructDecl>
        get() = structById.values.toList()

    private val enumById = mutableMapOf<DeclarationID, EnumDefImpl>()

    override val enums: List<EnumDef>
        get() = enumById.values.toList()

    private val typedefById = mutableMapOf<DeclarationID, TypedefDef>()

    override val typedefs: List<TypedefDef>
        get() = typedefById.values.toList()

    val functionByName = mutableMapOf<String, FunctionDecl>()

    override val functions: List<FunctionDecl>
        get() = functionByName.values.toList()

    override val macroConstants = mutableListOf<ConstantDef>()

    private fun getDeclarationId(cursor: CValue<CXCursor>): DeclarationID {
        val usr = clang_getCursorUSR(cursor).convertAndDispose()
        return DeclarationID(usr)
    }

    private fun getStructDeclAt(cursor: CValue<CXCursor>): StructDeclImpl {
        val declId = getDeclarationId(cursor)

        return structById.getOrPut(declId) {
            val cursorType = clang_getCursorType(cursor)
            val typeSpelling = clang_getTypeSpelling(cursorType).convertAndDispose()

            StructDeclImpl(typeSpelling)
        }
    }

    private fun getEnumDefAt(cursor: CValue<CXCursor>): EnumDefImpl {
        if (clang_isCursorDefinition(cursor) == 0) {
            TODO("support enum forward declarations")
        }

        val declId = getDeclarationId(cursor)

        return enumById.getOrPut(declId) {
            val cursorType = clang_getCursorType(cursor)
            val typeSpelling = clang_getTypeSpelling(cursorType).convertAndDispose()

            val baseType = convertType(clang_getEnumDeclIntegerType(cursor)) as PrimitiveType

            EnumDefImpl(typeSpelling, baseType)
        }
    }

    private fun builtinVaListType(type: CValue<CXType>, name: String, underlying: Type): Type {
        assert (type.kind == CXType_Typedef)
        val declarationId = DeclarationID("c:@T@$name")

        val structDeclaration = structById.getOrPut(declarationId) {
            StructDeclImpl(name).apply {
                val size = clang_Type_getSizeOf(type)
                val align = clang_Type_getAlignOf(type).toInt()
                val def = StructDefImpl(size, align, this, hasNaturalLayout = false)
                this.def = def
            }
        }
        assert (underlying is ConstArrayType)
        // So the result must feel like array:
        return ConstArrayType(RecordType(structDeclaration), 1)
    }

    fun getTypedef(type: CValue<CXType>): Type {
        val declCursor = clang_getTypeDeclaration(type)
        val name = getCursorSpelling(declCursor)

        val underlying = convertType(clang_getTypedefDeclUnderlyingType(declCursor))

        if (name == "__builtin_va_list" && underlying is ConstArrayType) {
            // On some platforms (e.g. macOS) libclang reports `__builtin_va_list` to be defined as array using
            //   typedef struct __va_list_tag __builtin_va_list[1]
            // while `struct __va_list_tag` is incomplete.
            // So `__builtin_va_list` gets declared as incorrect type, and requires some dirty hacks:
            return builtinVaListType(type, name, underlying)
        }


        if (name == "__gnuc_va_list"  || name == "va_list") {
            // TODO: fix GNUC varargs support.
            return UnsupportedType
        }

        if ((underlying is RecordType && underlying.decl.spelling.split(' ').last() == name) ||
                (underlying is EnumType && underlying.def.spelling.split(' ').last() == name)) {

            // special handling for:
            // typedef struct { ... } name;
            // typedef enum { ... } name;
            // FIXME: implement better solution
            return underlying
        }

        val declId = getDeclarationId(declCursor)
        val typedefDef = typedefById.getOrPut(declId) {

            TypedefDef(underlying, name)
        }

        return Typedef(typedefDef)
    }

    /**
     * Computes [StructDef.hasNaturalLayout] property.
     */
    fun structHasNaturalLayout(structDefCursor: CValue<CXCursor>): Boolean {
        val defKind = structDefCursor.kind

        when (defKind) {

            CXCursorKind.CXCursor_UnionDecl -> return false

            CXCursorKind.CXCursor_StructDecl -> memScoped {
                val hasAttributes = memScope.alloc<CInt32Var>()
                hasAttributes.value = 0
                clang_visitChildren(structDefCursor, staticCFunction { cursor, parent, clientData ->
                    if (clang_isAttribute(cursor.kind.value) != 0) {
                        val hasAttributes = clientData!!.reinterpret<CInt32Var>().pointed
                        hasAttributes.value = 1
                    }
                    CXChildVisitResult.CXChildVisit_Continue
                }, hasAttributes.ptr)

                return hasAttributes.value == 0
            }

            else -> throw IllegalArgumentException(defKind.toString())
        }
    }

    private fun convertCursorType(cursor: CValue<CXCursor>) =
        convertType(clang_getCursorType(cursor))

    fun convertType(type: CValue<CXType>): Type {
        val primitiveType = convertUnqualifiedPrimitiveType(type)
        if (primitiveType != UnsupportedType) {
            return primitiveType
        }

        val kind = type.kind
        return when (kind) {
            CXType_Elaborated -> convertType(clang_Type_getNamedType(type))

            CXType_Unexposed -> {
                val canonicalType = clang_getCanonicalType(type)
                if (canonicalType.kind != CXType_Unexposed) {
                    convertType(canonicalType)
                } else {
                    throw NotImplementedError()
                }
            }

            CXType_Void -> VoidType

            CXType_Typedef -> getTypedef(type)

            CXType_Record -> RecordType(getStructDeclAt(clang_getTypeDeclaration(type)))
            CXType_Enum -> EnumType(getEnumDefAt(clang_getTypeDeclaration(type)))

            CXType_Pointer -> {
                val pointeeType = clang_getPointeeType(type)
                val pointeeIsConst =
                        (clang_isConstQualifiedType(clang_getCanonicalType(pointeeType)) != 0)

                PointerType(convertType(pointeeType), pointeeIsConst = pointeeIsConst)
            }

            CXType_ConstantArray -> {
                val elemType = convertType(clang_getArrayElementType(type))
                val length = clang_getArraySize(type)
                ConstArrayType(elemType, length)
            }

            CXType_IncompleteArray -> {
                val elemType = convertType(clang_getArrayElementType(type))
                IncompleteArrayType(elemType)
            }

            CXType_FunctionProto -> {
                if (clang_isFunctionTypeVariadic(type) != 0) {
                    UnsupportedType
                } else {
                    val returnType = convertType(clang_getResultType(type))
                    val numArgs = clang_getNumArgTypes(type)
                    val paramTypes = (0..numArgs - 1).map {
                        convertType(clang_getArgType(type, it))
                    }
                    FunctionType(paramTypes, returnType)
                }
            }

            else -> UnsupportedType
        }
    }

    fun indexDeclaration(info: CXIdxDeclInfo): Unit {
        val cursor = info.cursor.readValue()
        val entityInfo = info.entityInfo.pointed!!
        val entityName = entityInfo.name.value?.toKString()
        val kind = entityInfo.kind.value
        when (kind) {
            CXIdxEntity_Field -> {
                val name = entityName!!
                val type = convertCursorType(cursor)
                val offset = clang_Cursor_getOffsetOfField(cursor)

                val container = info.semanticContainer.pointed!!
                val structDef = getStructDeclAt(container.cursor.readValue()).def!!
                structDef.fields.add(Field(name, type, offset))
            }

            CXIdxEntity_Struct, CXIdxEntity_Union -> {
                val structDecl = getStructDeclAt(cursor)
                if (clang_isCursorDefinition(cursor) != 0) {
                    val type = clang_getCursorType(cursor)
                    val size = clang_Type_getSizeOf(type)
                    val align = clang_Type_getAlignOf(type).toInt()
                    val hasNaturalLayout = structHasNaturalLayout(cursor)
                    structDecl.def = StructDefImpl(size, align, structDecl, hasNaturalLayout)
                }
            }

            CXIdxEntity_Function -> {
                val name = entityName!!
                val returnType = convertType(clang_getCursorResultType(cursor))
                val argNum = clang_Cursor_getNumArguments(cursor)
                val args = (0 .. argNum - 1).map {
                    val argCursor = clang_Cursor_getArgument(cursor, it)
                    val argName = getCursorSpelling(argCursor)
                    val type = convertCursorType(argCursor)
                    Parameter(argName, type)
                }

                val binaryName = when (language) {
                    Language.C -> clang_Cursor_getMangling(cursor).convertAndDispose()
                }

                val definitionCursor = clang_getCursorDefinition(cursor)
                val isDefined = (clang_Cursor_isNull(definitionCursor) == 0)

                functionByName[name] = FunctionDecl(name, args, returnType, binaryName, isDefined)
            }

            CXIdxEntity_Enum -> {
                getEnumDefAt(cursor)
            }

            CXIdxEntity_EnumConstant -> {
                val container = info.semanticContainer.pointed!!
                val name = entityName!!
                val value = clang_getEnumConstantDeclValue(info.cursor.readValue())

                val constants = getEnumDefAt(container.cursor.readValue()).constants
                val existingConstant = constants.find { it.name == name }
                if (existingConstant == null) {
                    val constant = EnumConstant(name, value, isExplicitlyDefined = !cursor.isLeaf())
                    constants.add(constant)
                } else {
                    // in some cases Clang may index the same definition multiple times; ignore redeclaration
                    // TODO: implement the same fix for structs
                    assert (existingConstant.value == value)
                }
            }
        }
    }

}

fun buildNativeIndexImpl(library: NativeLibrary): NativeIndex {
    val result = NativeIndexImpl(library.language)
    indexDeclarations(library, result)
    findMacroConstants(library, result)
    return result
}

private fun indexDeclarations(library: NativeLibrary, nativeIndex: NativeIndexImpl) {
    val index = clang_createIndex(0, 0)!!
    val indexAction = clang_IndexAction_create(index)!!
    try {
        val translationUnit = library.parse(index).ensureNoCompileErrors()
        try {
            memScoped {
                val callbacks = alloc<IndexerCallbacks>()

                val nativeIndexPtr = StableObjPtr.create(nativeIndex)
                val clientData = nativeIndexPtr.value

                try {
                    with(callbacks) {
                        abortQuery.value = null
                        diagnostic.value = null
                        enteredMainFile.value = null
                        ppIncludedFile.value = null
                        importedASTFile.value = null
                        startedTranslationUnit.value = null
                        indexDeclaration.value = staticCFunction { clientData, info ->
                            val nativeIndex = StableObjPtr.fromValue(clientData!!).get() as NativeIndexImpl
                            nativeIndex.indexDeclaration(info!!.pointed)
                        }
                        indexEntityReference.value = null
                    }

                    clang_indexTranslationUnit(indexAction, clientData,
                            callbacks.ptr, IndexerCallbacks.size.toInt(),
                            0, translationUnit)

                } finally {
                    nativeIndexPtr.dispose()
                }
            }
        } finally {
            clang_disposeTranslationUnit(translationUnit)
        }
    } finally {
        clang_IndexAction_dispose(indexAction)
        clang_disposeIndex(index)
    }
}
