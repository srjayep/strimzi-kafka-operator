/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.user.operator;

import io.strimzi.api.kafka.model.AclOperation;
import io.strimzi.api.kafka.model.AclResourcePatternType;
import io.strimzi.api.kafka.model.AclRuleType;
import io.strimzi.operator.common.operator.resource.ReconcileResult;
import io.strimzi.operator.user.model.acl.SimpleAclRule;
import io.strimzi.operator.user.model.acl.SimpleAclRuleResource;
import io.strimzi.operator.user.model.acl.SimpleAclRuleResourceType;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import kafka.security.auth.Acl;
import kafka.security.auth.Allow$;
import kafka.security.auth.Cluster$;
import kafka.security.auth.Describe$;
import kafka.security.auth.Group$;
import kafka.security.auth.Read$;
import kafka.security.auth.Resource;
import kafka.security.auth.SimpleAclAuthorizer;
import kafka.security.auth.Topic$;
import kafka.security.auth.Write$;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import scala.collection.Iterator;

import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(VertxExtension.class)
public class SimpleAclOperatorTest {
    protected static Vertx vertx;

    @BeforeAll
    public static void before() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    public static void after() {
        vertx.close();
    }

    @Test
    public void testGetUsersFromAcls(VertxTestContext context)  {
        SimpleAclAuthorizer mockAuthorizer = mock(SimpleAclAuthorizer.class);
        SimpleAclOperator aclOp = new SimpleAclOperator(vertx, mockAuthorizer);

        Checkpoint async = context.checkpoint();
        KafkaPrincipal foo = new KafkaPrincipal("User", "CN=foo");
        Acl fooAcl = new Acl(foo, Allow$.MODULE$, "*", Read$.MODULE$);
        KafkaPrincipal bar = new KafkaPrincipal("User", "CN=bar");
        Acl barAcl = new Acl(bar, Allow$.MODULE$, "*", Read$.MODULE$);
        KafkaPrincipal baz = new KafkaPrincipal("User", "baz");
        Acl bazAcl = new Acl(baz, Allow$.MODULE$, "*", Read$.MODULE$);
        KafkaPrincipal all = new KafkaPrincipal("User", "*");
        Acl allAcl = new Acl(all, Allow$.MODULE$, "*", Read$.MODULE$);
        KafkaPrincipal anonymous = new KafkaPrincipal("User", "ANONYMOUS");
        Acl anonymousAcl = new Acl(anonymous, Allow$.MODULE$, "*", Read$.MODULE$);
        Resource res1 = new Resource(Topic$.MODULE$, "my-topic", PatternType.LITERAL);
        Resource res2 = new Resource(Group$.MODULE$, "my-group", PatternType.LITERAL);
        scala.collection.immutable.Set<Acl> set1 = new scala.collection.immutable.Set.Set3<>(fooAcl, barAcl, allAcl);
        scala.collection.immutable.Set<Acl> set2 = new scala.collection.immutable.Set.Set2<>(bazAcl, anonymousAcl);
        scala.collection.immutable.Map<Resource, scala.collection.immutable.Set<Acl>> map = new scala.collection.immutable.Map.Map2<>(res1, set1, res2, set2);
        when(mockAuthorizer.getAcls()).thenReturn(map);

        ArgumentCaptor<KafkaPrincipal> principalCaptor = ArgumentCaptor.forClass(KafkaPrincipal.class);
        when(mockAuthorizer.getAcls(principalCaptor.capture())).thenReturn(map);
        async.flag();

        context.verify(() -> assertThat(aclOp.getUsersWithAcls(), is(new HashSet(asList("foo", "bar", "baz")))));
    }

    @Test
    public void testInternalCreate(VertxTestContext context) throws InterruptedException {
        SimpleAclAuthorizer mockAuthorizer = mock(SimpleAclAuthorizer.class);
        SimpleAclOperator aclOp = new SimpleAclOperator(vertx, mockAuthorizer);

        scala.collection.immutable.Map<Resource, scala.collection.immutable.Set<Acl>> map = new scala.collection.immutable.HashMap<Resource, scala.collection.immutable.Set<Acl>>();
        ArgumentCaptor<KafkaPrincipal> principalCaptor = ArgumentCaptor.forClass(KafkaPrincipal.class);
        when(mockAuthorizer.getAcls(principalCaptor.capture())).thenReturn(map);

        ArgumentCaptor<scala.collection.immutable.Set<Acl>> aclCaptor = ArgumentCaptor.forClass(scala.collection.immutable.Set.class);
        ArgumentCaptor<Resource> resourceCaptor = ArgumentCaptor.forClass(Resource.class);
        doNothing().when(mockAuthorizer).addAcls(aclCaptor.capture(), resourceCaptor.capture());

        SimpleAclRuleResource resource1 = new SimpleAclRuleResource("my-topic", SimpleAclRuleResourceType.CLUSTER, AclResourcePatternType.LITERAL);
        SimpleAclRuleResource resource = new SimpleAclRuleResource("my-topic", SimpleAclRuleResourceType.TOPIC, AclResourcePatternType.LITERAL);
        SimpleAclRule rule1 = new SimpleAclRule(AclRuleType.ALLOW, resource, "*", AclOperation.READ);
        SimpleAclRule rule2 = new SimpleAclRule(AclRuleType.ALLOW, resource, "*", AclOperation.WRITE);
        SimpleAclRule rule3 = new SimpleAclRule(AclRuleType.ALLOW, resource1, "*", AclOperation.DESCRIBE);

        KafkaPrincipal foo = new KafkaPrincipal("User", "CN=foo");
        Acl acl1 = new Acl(foo, Allow$.MODULE$, "*", Read$.MODULE$);
        scala.collection.immutable.Set<Acl> set1 = new scala.collection.immutable.Set.Set1<>(acl1);
        Acl acl2 = new Acl(foo, Allow$.MODULE$, "*", Write$.MODULE$);
        scala.collection.immutable.Set<Acl> set2 = new scala.collection.immutable.Set.Set1<>(acl2);
        Resource res1 = new Resource(Topic$.MODULE$, "my-topic", PatternType.LITERAL);
        Acl acl3 = new Acl(foo, Allow$.MODULE$, "*", Describe$.MODULE$);
        scala.collection.immutable.Set<Acl> set3 = new scala.collection.immutable.Set.Set1<>(acl3);
        Resource res2 = new Resource(Cluster$.MODULE$, "kafka-cluster", PatternType.LITERAL);

        Checkpoint async = context.checkpoint();
        Future<ReconcileResult<Set<SimpleAclRule>>> fut = aclOp.reconcile("CN=foo", new LinkedHashSet<>(asList(rule1, rule2, rule3)));
        fut.setHandler(res -> {
            context.verify(() -> assertThat(res.succeeded(), is(true)));

            List<scala.collection.immutable.Set<Acl>> capturedAcls = aclCaptor.getAllValues();
            List<Resource> capturedResource = resourceCaptor.getAllValues();

            context.verify(() -> assertThat(capturedAcls.size(), is(2)));
            context.verify(() -> assertThat(capturedResource.size(), is(2)));

            context.verify(() -> assertThat(res1.equals(capturedResource.get(0)) && res2.equals(capturedResource.get(1)) || res1.equals(capturedResource.get(1)) && res2.equals(capturedResource.get(0)), is(true)));

            if (capturedAcls.get(0).size() == 1) {
                context.verify(() -> assertThat(capturedAcls.get(0), is(set3)));
            } else {
                // the order can be changed
                if (capturedAcls.get(0).size() == 2) {
                    Iterator<Acl> iter = capturedAcls.get(0).iterator();
                    Acl aclFromSet1 = set1.head();
                    Acl aclFromSet2 = set2.head();

                    Acl capturedAcl1 = iter.next();
                    Acl capturedAcl2 = iter.next();

                    context.verify(() -> assertThat(aclFromSet1.equals(capturedAcl1) && aclFromSet2.equals(capturedAcl2) || aclFromSet1.equals(capturedAcl2) && aclFromSet1.equals(capturedAcl2), is(true)));
                }
            }

            if (capturedAcls.get(1).size() == 1) {
                context.verify(() -> assertThat(capturedAcls.get(1), is(set3)));
            } else {
                // the order can be changed
                if (capturedAcls.get(1).size() == 2) {
                    Iterator<Acl> iter = capturedAcls.get(1).iterator();
                    Acl aclFromSet1 = set1.head();
                    Acl aclFromSet2 = set2.head();

                    Acl capturedAcl1 = iter.next();
                    Acl capturedAcl2 = iter.next();

                    context.verify(() -> assertThat(aclFromSet1.equals(capturedAcl1) && aclFromSet2.equals(capturedAcl2) || aclFromSet1.equals(capturedAcl2) && aclFromSet1.equals(capturedAcl2), is(true)));
                }
            }
            async.flag();
        });
        if (!context.awaitCompletion(60, TimeUnit.SECONDS)) {
            context.failNow(new Throwable("Test timeout"));
        }
    }

    @Test
    public void testInternalUpdate(VertxTestContext context) throws InterruptedException {
        SimpleAclAuthorizer mockAuthorizer = mock(SimpleAclAuthorizer.class);
        SimpleAclOperator aclOp = new SimpleAclOperator(vertx, mockAuthorizer);

        SimpleAclRuleResource resource = new SimpleAclRuleResource("my-topic2", SimpleAclRuleResourceType.TOPIC, AclResourcePatternType.LITERAL);
        SimpleAclRule rule1 = new SimpleAclRule(AclRuleType.ALLOW, resource, "*", AclOperation.WRITE);

        KafkaPrincipal foo = new KafkaPrincipal("User", "CN=foo");
        Acl acl1 = new Acl(foo, Allow$.MODULE$, "*", Read$.MODULE$);
        scala.collection.immutable.Set<Acl> set1 = new scala.collection.immutable.Set.Set1<>(acl1);
        Acl acl2 = new Acl(foo, Allow$.MODULE$, "*", Write$.MODULE$);
        scala.collection.immutable.Set<Acl> set2 = new scala.collection.immutable.Set.Set1<>(acl2);
        Resource res1 = new Resource(Topic$.MODULE$, "my-topic", PatternType.LITERAL);
        Resource res2 = new Resource(Topic$.MODULE$, "my-topic2", PatternType.LITERAL);

        scala.collection.immutable.Map<Resource, scala.collection.immutable.Set<Acl>> map = new scala.collection.immutable.Map.Map1<>(res1, set1);
        ArgumentCaptor<KafkaPrincipal> principalCaptor = ArgumentCaptor.forClass(KafkaPrincipal.class);
        when(mockAuthorizer.getAcls(principalCaptor.capture())).thenReturn(map);

        ArgumentCaptor<scala.collection.immutable.Set<Acl>> aclCaptor = ArgumentCaptor.forClass(scala.collection.immutable.Set.class);
        ArgumentCaptor<Resource> resourceCaptor = ArgumentCaptor.forClass(Resource.class);
        doNothing().when(mockAuthorizer).addAcls(aclCaptor.capture(), resourceCaptor.capture());

        ArgumentCaptor<scala.collection.immutable.Set<Acl>> deleteAclCaptor = ArgumentCaptor.forClass(scala.collection.immutable.Set.class);
        ArgumentCaptor<Resource> deleterResourceCaptor = ArgumentCaptor.forClass(Resource.class);
        when(mockAuthorizer.removeAcls(deleteAclCaptor.capture(), deleterResourceCaptor.capture())).thenReturn(true);

        Checkpoint async = context.checkpoint();
        Future<Void> fut = aclOp.reconcile("CN=foo", new LinkedHashSet(asList(rule1)));
        fut.setHandler(res -> {
            context.verify(() -> assertThat(res.succeeded(), is(true)));

            List<scala.collection.immutable.Set<Acl>> capturedAcls = aclCaptor.getAllValues();
            List<Resource> capturedResource = resourceCaptor.getAllValues();
            List<scala.collection.immutable.Set<Acl>> deleteCapturedAcls = deleteAclCaptor.getAllValues();
            List<Resource> deleteCapturedResource = deleterResourceCaptor.getAllValues();

            context.verify(() -> assertThat(capturedAcls.size(), is(1)));
            context.verify(() -> assertThat(capturedResource.size(), is(1)));
            context.verify(() -> assertThat(deleteCapturedAcls.size(), is(1)));
            context.verify(() -> assertThat(deleteCapturedResource.size(), is(1)));

            context.verify(() -> assertThat(capturedResource.get(0), is(res2)));
            context.verify(() -> assertThat(deleteCapturedResource.get(0), is(res1)));

            context.verify(() -> assertThat(capturedAcls.get(0), is(set2)));
            context.verify(() -> assertThat(deleteCapturedAcls.get(0), is(set1)));
            async.flag();

        });
        if (!context.awaitCompletion(60, TimeUnit.SECONDS)) {
            context.failNow(new Throwable("Test timeout"));
        }
    }

    @Test
    public void testInternalDelete(VertxTestContext context) throws InterruptedException {
        SimpleAclAuthorizer mockAuthorizer = mock(SimpleAclAuthorizer.class);
        SimpleAclOperator aclOp = new SimpleAclOperator(vertx, mockAuthorizer);

        KafkaPrincipal foo = new KafkaPrincipal("User", "CN=foo");
        Acl acl1 = new Acl(foo, Allow$.MODULE$, "*", Read$.MODULE$);
        scala.collection.immutable.Set<Acl> set1 = new scala.collection.immutable.Set.Set1<>(acl1);
        Resource res1 = new Resource(Topic$.MODULE$, "my-topic", PatternType.LITERAL);

        scala.collection.immutable.Map<Resource, scala.collection.immutable.Set<Acl>> map = new scala.collection.immutable.Map.Map1<>(res1, set1);
        ArgumentCaptor<KafkaPrincipal> principalCaptor = ArgumentCaptor.forClass(KafkaPrincipal.class);
        when(mockAuthorizer.getAcls(principalCaptor.capture())).thenReturn(map);

        ArgumentCaptor<scala.collection.immutable.Set<Acl>> deleteAclCaptor = ArgumentCaptor.forClass(scala.collection.immutable.Set.class);
        ArgumentCaptor<Resource> deleterResourceCaptor = ArgumentCaptor.forClass(Resource.class);
        when(mockAuthorizer.removeAcls(deleteAclCaptor.capture(), deleterResourceCaptor.capture())).thenReturn(true);

        Checkpoint async = context.checkpoint();
        Future<ReconcileResult<Set<SimpleAclRule>>> fut = aclOp.reconcile("CN=foo", null);
        fut.setHandler(res -> {
            context.verify(() -> assertThat(res.succeeded(), is(true)));

            List<scala.collection.immutable.Set<Acl>> deleteCapturedAcls = deleteAclCaptor.getAllValues();
            List<Resource> deleteCapturedResource = deleterResourceCaptor.getAllValues();

            context.verify(() -> assertThat(deleteCapturedAcls.size(), is(1)));
            context.verify(() -> assertThat(deleteCapturedResource.size(), is(1)));

            context.verify(() -> assertThat(deleteCapturedResource.get(0), is(res1)));

            context.verify(() -> assertThat(deleteCapturedAcls.get(0), is(set1)));

            async.flag();
        });
        if (!context.awaitCompletion(60, TimeUnit.SECONDS)) {
            context.failNow(new Throwable("Test timeout"));
        }
    }
}
