/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.codehaus.groovy.classgen

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.decompiled.AsmDecompiler
import org.codehaus.groovy.ast.decompiled.AsmReferenceResolver
import org.codehaus.groovy.ast.decompiled.DecompiledClassNode
import org.codehaus.groovy.control.ClassNodeResolver
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.tools.javac.JavaAwareCompilationUnit
import org.junit.Test

import static groovy.test.GroovyAssert.assertScript
import static groovy.test.GroovyAssert.isAtLeastJdk
import static groovy.test.GroovyAssert.shouldFail
import static org.junit.Assume.assumeTrue

@CompileStatic
class RecordTest {
    @Test
    void testNativeRecordOnJDK16ByDefault() {
        assumeTrue(isAtLeastJdk('16.0'))
        assertScript '''
            record RecordJDK16plus(String name) {}
            assert java.lang.Record == RecordJDK16plus.class.getSuperclass()
        '''
    }

    @Test
    void testRecordLikeOnJDK16withTargetBytecode15() {
        assumeTrue(isAtLeastJdk('16.0'))
        def configuration = new CompilerConfiguration(targetBytecode: '15')
        assertScript(new GroovyShell(configuration), '''
            record RecordJDK16plus2(String name) {}
            assert java.lang.Record != RecordJDK16plus2.class.getSuperclass()
        ''')
    }

    @Test
    void testAttemptedNativeRecordWithTargetBytecode15ShouldFail() {
        assumeTrue(isAtLeastJdk('16.0'))
        def configuration = new CompilerConfiguration(targetBytecode: '15')
        def ex = shouldFail(new GroovyShell(configuration), '''
            import groovy.transform.*
            @RecordType(mode=RecordTypeMode.NATIVE)
            class RecordJDK16plus2 {
                String name
            }
            assert java.lang.Record != RecordJDK16plus2.class.getSuperclass()
        ''')
        assert ex.message.contains('Expecting JDK16+ but found 15 when attempting to create a native record')
    }

    @Test
    void testNativeRecordWithSuperClassShouldFail() {
        assumeTrue(isAtLeastJdk('16.0'))
        def ex = shouldFail('''
            import groovy.transform.*
            @RecordType
            class RecordJDK16plus2 extends ArrayList {
                String name
            }
            assert java.lang.Record != RecordJDK16plus2.class.getSuperclass()
        ''')
        assert ex.message.contains('Invalid superclass for native record found: java.util.ArrayList')
    }

    @Test
    void testNoNativeRecordOnJDK16WhenEmulating() {
        assumeTrue(isAtLeastJdk('16.0'))
        assertScript '''
            import groovy.transform.*
            @RecordOptions(mode=RecordTypeMode.EMULATE)
            record RecordJDK16plus2(String name) {}
            assert java.lang.Record != RecordJDK16plus2.class.getSuperclass()
        '''
    }

    @Test
    void testInnerRecordIsImplicitlyStatic() {
        assertScript '''
            class Test {
                record Point(int i, int j) {}
            }
            assert java.lang.reflect.Modifier.isStatic(Test$Point.modifiers)
        '''
    }

    @Test
    void testRecordWithDefaultParams() {
        assertScript '''
            record Point(int i = 5, int j = 10) {}
            assert new Point().toString() == 'Point[i=5, j=10]'
            assert new Point(50).toString() == 'Point[i=50, j=10]'
            assert new Point(50, 100).toString() == 'Point[i=50, j=100]'
            assert new Point([:]).toString() == 'Point[i=5, j=10]'
            assert new Point(i: 50).toString() == 'Point[i=50, j=10]'
            assert new Point(j: 100).toString() == 'Point[i=5, j=100]'
            assert new Point(i: 50, j: 100).toString() == 'Point[i=50, j=100]'
        '''
    }

    @Test
    void testNativeRecordOnJDK16plus() {
        assumeTrue(isAtLeastJdk('16.0'))
        assertScript '''
            import java.lang.annotation.*
            import java.lang.reflect.RecordComponent

            @Retention(RetentionPolicy.RUNTIME)
            @Target([ElementType.RECORD_COMPONENT])
            @interface NotNull {}

            @Retention(RetentionPolicy.RUNTIME)
            @Target([ElementType.RECORD_COMPONENT, ElementType.TYPE_USE])
            @interface NotNull2 {}
            
            @Retention(RetentionPolicy.RUNTIME)
            @Target([ElementType.TYPE_USE])
            @interface NotNull3 {}

            record Person(@NotNull @NotNull2 String name, int age, @NotNull2 @NotNull3 List<String> locations, String[] titles) {}

            RecordComponent[] rcs = Person.class.getRecordComponents()
            assert 4 == rcs.length

            assert 'name' == rcs[0].name && String.class == rcs[0].type
            Annotation[] annotations = rcs[0].getAnnotations()
            assert 2 == annotations.length
            assert NotNull.class == annotations[0].annotationType()
            assert NotNull2.class == annotations[1].annotationType()
            def typeAnnotations = rcs[0].getAnnotatedType().getAnnotations()
            assert 1 == typeAnnotations.length
            assert NotNull2.class == typeAnnotations[0].annotationType()

            assert 'age' == rcs[1].name && int.class == rcs[1].type

            assert 'locations' == rcs[2].name && List.class == rcs[2].type
            assert 'Ljava/util/List<Ljava/lang/String;>;' == rcs[2].genericSignature
            assert 'java.util.List<java.lang.String>' == rcs[2].genericType.toString()
            def annotations2 = rcs[2].getAnnotations()
            assert 1 == annotations2.length
            assert NotNull2.class == annotations2[0].annotationType()
            def typeAnnotations2 = rcs[2].getAnnotatedType().getAnnotations()
            assert 2 == typeAnnotations2.length
            assert NotNull2.class == typeAnnotations2[0].annotationType()
            assert NotNull3.class == typeAnnotations2[1].annotationType()

            assert 'titles' == rcs[3].name && String[].class == rcs[3].type
        '''
    }

    @Test
    @CompileDynamic
    void testNativeRecordOnJDK16plus_java() {
        assumeTrue(isAtLeastJdk('16.0'))

        def config = new CompilerConfiguration(
                targetDirectory: File.createTempDir(),
                jointCompilationOptions: [memStub: true]
        )
        def parentDir = File.createTempDir()
        try {
            def b = new File(parentDir, 'Person.java')
            b.write '''
                import java.lang.annotation.*;
                import java.util.*;

                public record Person(@NotNull @NotNull2 String name, int age, @NotNull2 @NotNull3 List<String> locations, String[] titles) {}

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.RECORD_COMPONENT})
                @interface NotNull {}

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
                @interface NotNull2 {}
                
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE_USE})
                @interface NotNull3 {}
            '''

            def loader = new GroovyClassLoader(this.class.classLoader)
            def cu = new JavaAwareCompilationUnit(config, loader)
            cu.addSources(b)
            cu.compile()

            Class personClazz = loader.loadClass("Person")
            Class notNullClazz = loader.loadClass("NotNull")
            Class notNull2Clazz = loader.loadClass("NotNull2")
            Class notNull3Clazz = loader.loadClass("NotNull3")

            def rcs = personClazz.recordComponents
            assert rcs.length == 4

            assert rcs[0].name == 'name' && String.class == rcs[0].type
            def annotations = rcs[0].annotations
            assert annotations.length == 2
            assert annotations[0].annotationType() == notNullClazz
            assert annotations[1].annotationType() == notNull2Clazz
            def typeAnnotations = rcs[0].getAnnotatedType().getAnnotations()
            assert typeAnnotations.length == 1
            assert notNull2Clazz == typeAnnotations[0].annotationType()

            assert rcs[1].name == 'age'       && rcs[1].type == int.class
            assert rcs[2].name == 'locations' && rcs[2].type == List.class
            assert rcs[3].name == 'titles'    && rcs[3].type == String[].class

            assert rcs[2].genericSignature == 'Ljava/util/List<Ljava/lang/String;>;'
            assert rcs[2].genericType as String == 'java.util.List<java.lang.String>'

            def annotations2 = rcs[2].annotations
            assert annotations2.length == 1
            assert annotations2[0].annotationType() == notNull2Clazz
            def typeAnnotations2 = rcs[2].annotatedType.annotations
            assert typeAnnotations2.length == 2
            assert typeAnnotations2[0].annotationType() == notNull2Clazz
            assert typeAnnotations2[1].annotationType() == notNull3Clazz

            ClassNode personClassNode = ClassHelper.make(personClazz)
            ClassNode notNullClassNode = ClassHelper.make(notNullClazz)
            ClassNode notNull2ClassNode = ClassHelper.make(notNull2Clazz)
            ClassNode notNull3ClassNode = ClassHelper.make(notNull3Clazz)
            doTestNativeRecordClassNode(personClassNode, notNullClassNode, notNull2ClassNode, notNull3ClassNode)

            def resource = loader.getResource(personClazz.getName().replace('.', '/') + '.class')
            def stub = AsmDecompiler.parseClass(resource)
            def unit = new CompilationUnit(loader)
            def personDecompiledClassNode = new DecompiledClassNode(stub, new AsmReferenceResolver(new ClassNodeResolver(), unit))
            doTestNativeRecordClassNode(personDecompiledClassNode, notNullClassNode, notNull2ClassNode, notNull3ClassNode)
        } finally {
            parentDir.deleteDir()
            config.targetDirectory.deleteDir()
        }
    }

    private static void doTestNativeRecordClassNode(ClassNode personClassNode, ClassNode notNullClassNode, ClassNode notNull2ClassNode, ClassNode notNull3ClassNode) {
        assert personClassNode.isRecord()
        def rcns = personClassNode.getRecordComponentNodes()
        assert 4 == rcns.size()
        assert 'name' == rcns[0].name && ClassHelper.STRING_TYPE == rcns[0].type
        List<AnnotationNode> annotationNodes = rcns[0].getAnnotations()
        assert 2 == annotationNodes.size()
        assert notNullClassNode == annotationNodes[0].getClassNode()
        assert notNull2ClassNode == annotationNodes[1].getClassNode()
        def typeAnnotationNodes = rcns[0].getType().getTypeAnnotations()
        assert 1 == typeAnnotationNodes.size()
        assert notNull2ClassNode == typeAnnotationNodes[0].getClassNode()

        assert 'age' == rcns[1].name && ClassHelper.int_TYPE == rcns[1].type

        assert 'locations' == rcns[2].name && ClassHelper.LIST_TYPE == rcns[2].type
        def genericsTypes = rcns[2].type.genericsTypes
        assert 1 == genericsTypes.size()
        assert ClassHelper.STRING_TYPE == genericsTypes[0].type
        def annotationNodes2 = rcns[2].getAnnotations()
        assert 1 == annotationNodes2.size()
        assert notNull2ClassNode == annotationNodes2[0].getClassNode()
        def typeAnnotationNodes2 = rcns[2].getType().getTypeAnnotations()
        assert 2 == typeAnnotationNodes2.size()
        assert notNull2ClassNode == typeAnnotationNodes2[0].getClassNode()
        assert notNull3ClassNode == typeAnnotationNodes2[1].getClassNode()

        assert 'titles' == rcns[3].name && ClassHelper.make(String[].class) == rcns[3].type
    }

    @Test
    void testNativeRecordOnJDK16plus2_java() {
        assumeTrue(isAtLeastJdk('16.0'))
        assertScript '''
            import org.codehaus.groovy.ast.*

            def cn = ClassHelper.make(jdk.net.UnixDomainPrincipal.class)
            assert cn.isRecord()
            def rcns = cn.getRecordComponentNodes()
            assert 2 == rcns.size()
            assert 'user' == rcns[0].name && 'java.nio.file.attribute.UserPrincipal' == rcns[0].type.name
            assert 'group' == rcns[1].name && 'java.nio.file.attribute.GroupPrincipal' == rcns[1].type.name
        '''
    }

    @Test
    void testNativeRecordOnJDK16plus2() {
        assumeTrue(isAtLeastJdk('16.0'))
        assertScript '''
            @groovy.transform.CompileStatic
            record Record(String name, int x0, int x1, int x2, int x3, int x4,
                                    int x5, int x6, int x7, int x8, int x9, int x10, int x11, int x12, int x13, int x14,
                                    int x15, int x16, int x17, int x18, int x19, int x20) {
                public Record {
                    x1 = -x1
                }
            }

            def r = new Record('someRecord', 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
            def expected = 'Record[name=someRecord, x0=0, x1=-1, x2=2, x3=3, x4=4, x5=5, x6=6, x7=7, x8=8, x9=9, x10=10, x11=11, x12=12, x13=13, x14=14, x15=15, x16=16, x17=17, x18=18, x19=19, x20=20]'
            assert expected == r.toString()
        '''
        assertScript '''
            import groovy.transform.*
            @CompileStatic
            @ToString(includeNames=true)
            record Record(String name, int x0, int x1, int x2, int x3, int x4,
                                    int x5, int x6, int x7, int x8, int x9, int x10, int x11, int x12, int x13, int x14,
                                    int x15, int x16, int x17, int x18, int x19, int x20) {
                public Record {
                    x1 = -x1
                }
            }

            def r = new Record('someRecord', 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
            def expected = 'Record(name:someRecord, x0:0, x1:-1, x2:2, x3:3, x4:4, x5:5, x6:6, x7:7, x8:8, x9:9, x10:10, x11:11, x12:12, x13:13, x14:14, x15:15, x16:16, x17:17, x18:18, x19:19, x20:20)'
            assert expected == r.toString()
        '''
    }

    @Test
    void testCoerce() {
        assertScript '''
            @groovy.transform.CompileDynamic
            record PersonDynamic(String name, int age) {}
            record PersonStatic(String name, int age) {}
            
            def testDynamic() {
                PersonDynamic p = ['Daniel', 37]
                assert 'Daniel' == p.name()
                assert 37 == p.age()
                
                PersonDynamic p2 = [age: 37, name: 'Daniel']
                assert 'Daniel' == p2.name()
                assert 37 == p2.age()
                
                PersonStatic p3 = ['Daniel', 37]
                assert 'Daniel' == p3.name()
                assert 37 == p3.age()
                
                PersonStatic p4 = [age: 37, name: 'Daniel']
                assert 'Daniel' == p4.name()
                assert 37 == p4.age()
            }
            testDynamic()
            
            @groovy.transform.CompileStatic
            def testStatic() {
                PersonStatic p = ['Daniel', 37]
                assert 'Daniel' == p.name()
                assert 37 == p.age()
                
                PersonStatic p2 = [age: 37, name: 'Daniel']
                assert 'Daniel' == p2.name()
                assert 37 == p2.age()
                
                PersonDynamic p3 = ['Daniel', 37]
                assert 'Daniel' == p3.name()
                assert 37 == p3.age()
                
                PersonDynamic p4 = [age: 37, name: 'Daniel']
                assert 'Daniel' == p4.name()
                assert 37 == p4.age()
            }
            testStatic()
        '''
    }
}
