package com.cczxcsc.testing;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContextManager;

// Maven 测试执行器无法下载时，在已有依赖上运行现有断言和 Spring 测试上下文。
public final class LocalTestRunner {
    public static void main(String[] args) throws Exception {
        String[] classes = {"com.cczxcsc.CoreTest", "com.cczxcsc.SqliteMigrationTest", "com.cczxcsc.LegacyImportTest", "com.cczxcsc.ProjectDocumentTest", "com.cczxcsc.SecurityPolicyTest", "com.cczxcsc.ApiSqliteTest", "com.cczxcsc.ApiSecurityTest"};
        int passed = 0;
        int failed = 0;
        for (String name : classes) {
            Class<?> type = Class.forName(name);
            TestContextManager manager = type.isAnnotationPresent(SpringBootTest.class) ? new TestContextManager(type) : null;
            if (manager != null) manager.beforeTestClass();
            Method[] methods = Arrays.stream(type.getMethods()).filter(method -> method.isAnnotationPresent(Test.class)).sorted(Comparator.comparing(Method::getName)).toArray(Method[]::new);
            try {
                for (Method method : methods) {
                    Object instance = type.getConstructor().newInstance();
                    Throwable failure = null;
                    if (manager != null) {
                        manager.prepareTestInstance(instance);
                        manager.beforeTestMethod(instance, method);
                    }
                    try {
                        for (Method before : type.getMethods()) if (before.isAnnotationPresent(BeforeEach.class)) before.invoke(instance);
                        if (manager != null) manager.beforeTestExecution(instance, method);
                        method.invoke(instance);
                        passed++;
                        System.out.println("PASS " + name + " / " + method.getName());
                    } catch (InvocationTargetException exception) {
                        failure = exception.getTargetException();
                        failed++;
                        System.err.println("FAIL " + name + " / " + method.getName());
                        failure.printStackTrace();
                    } finally {
                        if (manager != null) {
                            manager.afterTestExecution(instance, method, failure);
                            manager.afterTestMethod(instance, method, failure);
                        }
                    }
                }
            } finally {
                if (manager != null) {
                    manager.afterTestClass();
                    manager.getTestContext().markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE);
                }
            }
        }
        System.out.println("LOCAL TEST RESULTS: passed=" + passed + ", failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
