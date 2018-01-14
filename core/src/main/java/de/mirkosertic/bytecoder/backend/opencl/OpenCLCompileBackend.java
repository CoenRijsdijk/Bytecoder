/*
 * Copyright 2018 Mirko Sertic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.mirkosertic.bytecoder.backend.opencl;

import de.mirkosertic.bytecoder.backend.CompileBackend;
import de.mirkosertic.bytecoder.backend.CompileOptions;
import de.mirkosertic.bytecoder.core.BytecodeLinkedClass;
import de.mirkosertic.bytecoder.core.BytecodeLinkerContext;
import de.mirkosertic.bytecoder.core.BytecodeLoader;
import de.mirkosertic.bytecoder.core.BytecodeMethod;
import de.mirkosertic.bytecoder.core.BytecodeMethodSignature;
import de.mirkosertic.bytecoder.core.BytecodeObjectTypeRef;
import de.mirkosertic.bytecoder.core.BytecodePackageReplacer;
import de.mirkosertic.bytecoder.relooper.Relooper;
import de.mirkosertic.bytecoder.ssa.Expression;
import de.mirkosertic.bytecoder.ssa.ExpressionList;
import de.mirkosertic.bytecoder.ssa.ExpressionListContainer;
import de.mirkosertic.bytecoder.ssa.GetFieldValue;
import de.mirkosertic.bytecoder.ssa.GraphNode;
import de.mirkosertic.bytecoder.ssa.InitVariableExpression;
import de.mirkosertic.bytecoder.ssa.NaiveProgramGenerator;
import de.mirkosertic.bytecoder.ssa.Program;
import de.mirkosertic.bytecoder.ssa.ProgramGenerator;
import de.mirkosertic.bytecoder.ssa.ProgramGeneratorFactory;
import de.mirkosertic.bytecoder.ssa.PutFieldExpression;
import de.mirkosertic.bytecoder.ssa.Value;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

public class OpenCLCompileBackend implements CompileBackend<OpenCLCompileResult> {

    private final BytecodeLoader loader;
    private final ProgramGeneratorFactory programGeneratorFactory;

    public OpenCLCompileBackend() {
        loader = new BytecodeLoader(getClass().getClassLoader(), new BytecodePackageReplacer());
        programGeneratorFactory = NaiveProgramGenerator.FACTORY;
    }

    public BytecodeMethodSignature signatureFrom(Method aMethod) {
        return loader.getSignatureParser().toMethodSignature(aMethod);
    }

    @Override
    public OpenCLCompileResult generateCodeFor(CompileOptions aOptions, BytecodeLinkerContext aLinkerContext, Class aEntryPointClass, String aEntryPointMethodName, BytecodeMethodSignature aEntryPointSignatue) {

        BytecodeLinkerContext theLinkerContext = new BytecodeLinkerContext(loader, aOptions.getLogger());
        BytecodeLinkedClass theKernelClass = theLinkerContext.linkClass(BytecodeObjectTypeRef.fromRuntimeClass(aEntryPointClass));

        theKernelClass.linkVirtualMethod(aEntryPointMethodName, aEntryPointSignatue);

        // Now we need to retrive the method
        Set<BytecodeMethod> theBytecodeMethods = new HashSet<>();
        theKernelClass.forEachMethod(theBytecodeMethods::add);

        if (theBytecodeMethods.size() != 1) {
            throw new IllegalArgumentException("Invalid number of loaded methods : " + theBytecodeMethods.size());
        }

        ProgramGenerator theGenerator = programGeneratorFactory.createFor(aLinkerContext);
        Program theSSAProgram = theGenerator.generateFrom(theKernelClass.getBytecodeClass(), theBytecodeMethods.iterator().next());

        //Run optimizer
        aOptions.getOptimizer().optimize(theSSAProgram.getControlFlowGraph(), aLinkerContext);

        // Ok, at this point we have to map kernel arguments
        // Every member of the kernel class becomes a kernel function argument
        OpenCLInputOutputs theInputOutputs;
        try {
            theInputOutputs = inputOutputsFor(aEntryPointClass, theSSAProgram);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // And then we ca pass it to the code generator to generate the kernel code

        StringWriter theStrWriter = new StringWriter();
        OpenCLWriter theSSAWriter = new OpenCLWriter(aOptions, theSSAProgram, "", new PrintWriter(theStrWriter), aLinkerContext, theInputOutputs);

        // Try to reloop it!
        if (aOptions.isRelooper()) {
            try {
                Relooper theRelooper = new Relooper();
                Relooper.Block theReloopedBlock = theRelooper.reloop(theSSAProgram.getControlFlowGraph());

                theSSAWriter.printRelooped(theReloopedBlock);
            } catch (Exception e) {
                throw new IllegalStateException("Error relooping cfg", e);
            }
        } else {
            throw new IllegalArgumentException("Code generation only supported with Relooper enabled!");
        }

        return new OpenCLCompileResult(theInputOutputs, theStrWriter.toString());
    }

    @Override
    public String generatedFileName() {
        return "unused";
    }

    private OpenCLInputOutputs inputOutputsFor(Class aSourceClass, Program aProgram) throws NoSuchFieldException {
        OpenCLInputOutputs theResult = new OpenCLInputOutputs();
        for (GraphNode theNode : aProgram.getControlFlowGraph().getKnownNodes()) {
            fillInputOutputs(aSourceClass, theNode.getExpressions(), theResult);
        }
        return theResult;
    }

    private void fillInputOutputs(Class aSourceClass, ExpressionList aExpressionList, OpenCLInputOutputs aInputOutputs) throws NoSuchFieldException {
        for (Expression theExpression : aExpressionList.toList()) {
            if (theExpression instanceof ExpressionListContainer) {
                ExpressionListContainer theContainer = (ExpressionListContainer) theExpression;
                for (ExpressionList theList : theContainer.getExpressionLists()) {
                    fillInputOutputs(aSourceClass, theList, aInputOutputs);
                }
            }
            if (theExpression instanceof PutFieldExpression) {
                PutFieldExpression theField = (PutFieldExpression) theExpression;
                Field theJVMField = aSourceClass.getDeclaredField(theField.getField().getNameAndTypeIndex().getNameAndType().getNameIndex().getName().stringValue());

                aInputOutputs.registerWriteTo(theJVMField);

                registerInputs(aSourceClass, theField.getValue(), aInputOutputs);
            }
            if (theExpression instanceof InitVariableExpression) {
                InitVariableExpression theInit = (InitVariableExpression) theExpression;
                registerInputs(aSourceClass, theInit.getValue(), aInputOutputs);
            }
        }
    }

    private void registerInputs(Class aSourceClass, Value aValue, OpenCLInputOutputs aInputOutputs) throws NoSuchFieldException {
        if (aValue instanceof GetFieldValue) {
            GetFieldValue theGetField = (GetFieldValue) aValue;

            Field theJVMField = aSourceClass.getDeclaredField(theGetField.getField().getNameAndTypeIndex().getNameAndType().getNameIndex().getName().stringValue());

            aInputOutputs.registerReadFrom(theJVMField);
        }
    }
}