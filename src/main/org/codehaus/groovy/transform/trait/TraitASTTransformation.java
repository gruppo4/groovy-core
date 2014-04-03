/*
 * Copyright 2003-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.transform.trait;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.classgen.VariableScopeVisitor;
import org.codehaus.groovy.classgen.Verifier;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;
import org.codehaus.groovy.transform.AbstractASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Handles generation of code for the @Trait annotation. A class annotated with @Trait will generate, instead: <ul>
 * <li>an <i>interface</i> with the same name</li> <li>an utility inner class that will be used by the compiler to
 * handle the trait</li> </ul>
 *
 * @author Cedric Champeau
 */
@GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
public class TraitASTTransformation extends AbstractASTTransformation {

    private SourceUnit unit;

    public void visit(ASTNode[] nodes, SourceUnit source) {
        AnnotatedNode parent = (AnnotatedNode) nodes[1];
        AnnotationNode anno = (AnnotationNode) nodes[0];
        if (!Traits.TRAIT_CLASSNODE.equals(anno.getClassNode())) return;
        unit = source;
        init(nodes, source);
        if (parent instanceof ClassNode) {
            ClassNode cNode = (ClassNode) parent;
            checkNotInterface(cNode, Traits.TRAIT_TYPE_NAME);
            checkNoConstructor(cNode);
            replaceExtendsByImplements(cNode);
            createHelperClass(cNode);
        }
    }

    private void replaceExtendsByImplements(final ClassNode cNode) {
        ClassNode superClass = cNode.getSuperClass();
        if (Traits.isTrait(superClass)) {
            // move from super class to interface;
            cNode.setSuperClass(ClassHelper.OBJECT_TYPE);
            cNode.addInterface(superClass);
            resolveScope(cNode);
        }
    }

    private void resolveScope(final ClassNode cNode) {
        // we need to resolve again!
        VariableScopeVisitor scopeVisitor = new VariableScopeVisitor(unit);
        scopeVisitor.visitClass(cNode);
    }

    private void checkNoConstructor(final ClassNode cNode) {
        if (!cNode.getDeclaredConstructors().isEmpty()) {
            addError("Error processing trait '" + cNode.getName() + "'. " +
                    " Constructors are not allowed.", cNode);
        }
    }

    private static void fixGenerics(MethodNode mn, ClassNode cNode) {
        if (!cNode.isUsingGenerics()) return;
        mn.setGenericsTypes(cNode.getGenericsTypes());
    }

    private void createHelperClass(final ClassNode cNode) {
        ClassNode helper = new InnerClassNode(
                cNode,
                Traits.helperClassName(cNode),
                ACC_PUBLIC | ACC_STATIC | ACC_ABSTRACT | ACC_SYNTHETIC,
                ClassHelper.OBJECT_TYPE,
                ClassNode.EMPTY_ARRAY,
                null
        );
        cNode.setModifiers(ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT);

        checkInnerClasses(cNode);

        MethodNode initializer = new MethodNode(
                Traits.STATIC_INIT_METHOD,
                ACC_STATIC | ACC_PUBLIC | ACC_SYNTHETIC,
                ClassHelper.VOID_TYPE,
                new Parameter[]{new Parameter(cNode.getPlainNodeReference(), Traits.THIS_OBJECT)},
                ClassNode.EMPTY_ARRAY,
                new BlockStatement()
        );
        fixGenerics(initializer, cNode);
        helper.addMethod(initializer);

        // apply the verifier to have the property nodes generated
        generatePropertyMethods(cNode);

        // prepare fields
        List<FieldNode> fields = new ArrayList<FieldNode>();
        Set<String> fieldNames = new HashSet<String>();
        for (FieldNode field : cNode.getFields()) {
            if (!"metaClass".equals(field.getName()) && (!field.isSynthetic() || field.getName().indexOf('$') < 0)) {
                fields.add(field);
                fieldNames.add(field.getName());
            }
        }
        ClassNode fieldHelper = null;
        if (!fields.isEmpty()) {
            fieldHelper = new InnerClassNode(
                    cNode,
                    Traits.fieldHelperClassName(cNode),
                    ACC_STATIC | ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
                    ClassHelper.OBJECT_TYPE
            );
        }

        // add methods
        List<MethodNode> methods = cNode.getMethods();
        List<MethodNode> privateMethods = new LinkedList<MethodNode>();
        for (final MethodNode methodNode : methods) {
            boolean declared = methodNode.getDeclaringClass() == cNode;
            if (declared) {
                if (!methodNode.isSynthetic() && methodNode.isProtected()) {
                    unit.addError(new SyntaxException("Cannot have protected method in a trait (" + cNode.getName() + "#" + methodNode.getTypeDescriptor() + ")",
                            methodNode.getLineNumber(), methodNode.getColumnNumber()));
                    return;
                }
                helper.addMethod(processMethod(cNode, methodNode, fieldHelper, fieldNames));
                if (methodNode.isPrivate()) {
                    privateMethods.add(methodNode);
                }
            }
        }

        // remove private methods
        for (MethodNode privateMethod : privateMethods) {
            cNode.removeMethod(privateMethod);
        }

        // add fields
        for (FieldNode field : fields) {
            processField(field, initializer, fieldHelper);
        }

        // clear properties to avoid generation of methods
        cNode.getProperties().clear();

        // copy annotations
        List<AnnotationNode> copied = new LinkedList<AnnotationNode>();
        List<AnnotationNode> notCopied = new LinkedList<AnnotationNode>();
        copyAnnotatedNodeAnnotations(cNode, copied, notCopied);
        if (!notCopied.isEmpty()) {
            for (AnnotationNode node : notCopied) {
                unit.addError(new SyntaxException("Annotation not supported onto traits", node.getLineNumber(), node.getColumnNumber()));
            }
        }
        helper.addAnnotations(copied);

        fields = new ArrayList<FieldNode>(cNode.getFields()); // reuse the full list of fields
        for (FieldNode field : fields) {
            cNode.removeField(field.getName());
        }

        unit.getAST().addClass(helper);
        if (fieldHelper != null) {
            unit.getAST().addClass(fieldHelper);
        }
    }

    private void checkInnerClasses(final ClassNode cNode) {
        Iterator<InnerClassNode> it = cNode.getInnerClasses();
        while (it.hasNext()) {
            InnerClassNode origin = it.next();
            if ((origin.getModifiers() & ACC_STATIC) == 0) {
                unit.addError(new SyntaxException("Cannot have non-static inner class inside a trait ("+origin.getName()+")", origin.getLineNumber(), origin.getColumnNumber()));
            }
        }
    }

    private void generatePropertyMethods(final ClassNode cNode) {
        for (PropertyNode node : cNode.getProperties()) {
            processProperty(cNode, node);
        }
    }

    /**
     * Mostly copied from the {@link Verifier} class but does *not* generate bytecode
     *
     * @param cNode
     * @param node
     */
    private static void processProperty(final ClassNode cNode, PropertyNode node) {
        String name = node.getName();
        FieldNode field = node.getField();
        int propNodeModifiers = node.getModifiers();

        String getterName = "get" + Verifier.capitalize(name);
        String setterName = "set" + Verifier.capitalize(name);

        // GROOVY-3726: clear volatile, transient modifiers so that they don't get applied to methods
        if ((propNodeModifiers & Modifier.VOLATILE) != 0) {
            propNodeModifiers = propNodeModifiers - Modifier.VOLATILE;
        }
        if ((propNodeModifiers & Modifier.TRANSIENT) != 0) {
            propNodeModifiers = propNodeModifiers - Modifier.TRANSIENT;
        }

        Statement getterBlock = node.getGetterBlock();
        if (getterBlock == null) {
            MethodNode getter = cNode.getGetterMethod(getterName);
            if (getter == null && ClassHelper.boolean_TYPE == node.getType()) {
                String secondGetterName = "is" + Verifier.capitalize(name);
                getter = cNode.getGetterMethod(secondGetterName);
            }
            if (!node.isPrivate() && methodNeedsReplacement(cNode, getter)) {
                getterBlock = new ExpressionStatement(new FieldExpression(field));
            }
        }
        Statement setterBlock = node.getSetterBlock();
        if (setterBlock == null) {
            // 2nd arg false below: though not usual, allow setter with non-void return type
            MethodNode setter = cNode.getSetterMethod(setterName, false);
            if (!node.isPrivate() &&
                    (propNodeModifiers & ACC_FINAL) == 0 &&
                    methodNeedsReplacement(cNode, setter)) {
                setterBlock = new ExpressionStatement(
                        new BinaryExpression(
                                new FieldExpression(field),
                                Token.newSymbol(Types.EQUAL, 0, 0),
                                new VariableExpression("value")
                        )
                );
            }
        }

        if (getterBlock != null) {
            MethodNode getter =
                    new MethodNode(getterName, propNodeModifiers, node.getType(), Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, getterBlock);
            getter.setSynthetic(true);
            fixGenerics(getter, cNode);
            cNode.addMethod(getter);

            if (ClassHelper.boolean_TYPE == node.getType() || ClassHelper.Boolean_TYPE == node.getType()) {
                String secondGetterName = "is" + Verifier.capitalize(name);
                MethodNode secondGetter =
                        new MethodNode(secondGetterName, propNodeModifiers, node.getType(), Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, getterBlock);
                secondGetter.setSynthetic(true);
                fixGenerics(secondGetter, cNode);
                cNode.addMethod(secondGetter);
            }
        }
        if (setterBlock != null) {
            Parameter[] setterParameterTypes = {new Parameter(node.getType(), "value")};
            VariableExpression var = (VariableExpression) ((BinaryExpression) ((ExpressionStatement) setterBlock).getExpression()).getRightExpression();
            var.setAccessedVariable(setterParameterTypes[0]);
            MethodNode setter =
                    new MethodNode(setterName, propNodeModifiers, ClassHelper.VOID_TYPE, setterParameterTypes, ClassNode.EMPTY_ARRAY, setterBlock);
            setter.setSynthetic(true);
            fixGenerics(setter, cNode);
            cNode.addMethod(setter);
        }
    }

    private static boolean methodNeedsReplacement(ClassNode classNode, MethodNode m) {
        // no method found, we need to replace
        if (m == null) return true;
        // method is in current class, nothing to be done
        if (m.getDeclaringClass() == classNode) return false;
        // do not overwrite final
        if ((m.getModifiers() & ACC_FINAL) != 0) return false;
        return true;
    }


    private void processField(final FieldNode field, final MethodNode initializer, final ClassNode fieldHelper) {
        Expression initialExpression = field.getInitialExpression();
        if (initialExpression != null) {
            VariableExpression thisObject = new VariableExpression(initializer.getParameters()[0]);
            BlockStatement code = (BlockStatement) initializer.getCode();
            MethodCallExpression mce = new MethodCallExpression(
                    new CastExpression(fieldHelper, thisObject),
                    Traits.helperSetterName(field),
                    initialExpression
            );
            mce.setImplicitThis(false);
            mce.setSourcePosition(initialExpression);
            code.addStatement(new ExpressionStatement(mce));
        }
        // define setter/getter helper methods
        fieldHelper.addMethod(
                Traits.helperSetterName(field),
                ACC_PUBLIC | ACC_ABSTRACT,
                ClassHelper.VOID_TYPE,
                new Parameter[]{new Parameter(field.getOriginType(), "val")},
                ClassNode.EMPTY_ARRAY,
                null
        );
        fieldHelper.addMethod(
                Traits.helperGetterName(field),
                ACC_PUBLIC | ACC_ABSTRACT,
                field.getOriginType(),
                Parameter.EMPTY_ARRAY,
                ClassNode.EMPTY_ARRAY,
                null
        );

        // dummy fields are only used to carry annotations
        FieldNode dummyField = new FieldNode(
                Traits.remappedFieldName(field.getOwner(),field.getName()),
                ACC_STATIC|ACC_PUBLIC | ACC_FINAL | ACC_SYNTHETIC,
                field.getOriginType(),
                fieldHelper,
                null
        );
        // copy annotations from field to dummy field
        List<AnnotationNode> copied = new LinkedList<AnnotationNode>();
        List<AnnotationNode> notCopied = new LinkedList<AnnotationNode>();
        copyAnnotatedNodeAnnotations(field, copied, notCopied);
        dummyField.addAnnotations(copied);
        fieldHelper.addField(dummyField);
    }

    private MethodNode processMethod(ClassNode traitClass, MethodNode methodNode, ClassNode fieldHelper, Collection<String> knownFields) {
        Parameter[] initialParams = methodNode.getParameters();
        Parameter[] newParams = new Parameter[initialParams.length + 1];
        newParams[0] = new Parameter(traitClass.getPlainNodeReference(), Traits.THIS_OBJECT);
        System.arraycopy(initialParams, 0, newParams, 1, initialParams.length);
        final int mod = methodNode.isPrivate()?ACC_PRIVATE:ACC_PUBLIC;
        MethodNode mNode = new MethodNode(
                methodNode.getName(),
                mod | ACC_STATIC,
                methodNode.getReturnType(),
                newParams,
                methodNode.getExceptions(),
                processBody(new VariableExpression(newParams[0]), methodNode.getCode(), traitClass, fieldHelper, knownFields)
        );
        mNode.setSourcePosition(methodNode);
        List<AnnotationNode> copied = new LinkedList<AnnotationNode>();
        List<AnnotationNode> notCopied = new LinkedList<AnnotationNode>();
        AbstractASTTransformation.copyAnnotatedNodeAnnotations(methodNode, copied, notCopied);
        mNode.addAnnotations(copied);
        if (methodNode.isAbstract()) {
            mNode.setModifiers(ACC_PUBLIC | ACC_ABSTRACT);
        } else {
            methodNode.addAnnotation(new AnnotationNode(Traits.IMPLEMENTED_CLASSNODE));
        }
        methodNode.setCode(null);

        if (!methodNode.isPrivate()) {
            methodNode.setModifiers(ACC_PUBLIC | ACC_ABSTRACT);
        }
        return mNode;
    }

    private Statement processBody(VariableExpression thisObject, Statement code, ClassNode trait, ClassNode fieldHelper, Collection<String> knownFields) {
        if (code == null) return null;
        SuperCallTraitTransformer superTrn = new SuperCallTraitTransformer(unit);
        code.visit(superTrn);
        TraitReceiverTransformer trn = new TraitReceiverTransformer(thisObject, unit, trait, fieldHelper, knownFields);
        code.visit(trn);
        return code;
    }

}