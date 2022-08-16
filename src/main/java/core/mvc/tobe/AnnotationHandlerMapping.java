package core.mvc.tobe;

import com.google.common.collect.Maps;
import core.annotation.web.Controller;
import core.annotation.web.RequestMapping;
import core.annotation.web.RequestMethod;
import core.di.factory.BeanFactory;
import core.exception.BeanFactoryInitFailedException;
import core.mvc.HandlerMapping;
import core.mvc.tobe.support.ArgumentResolver;
import core.mvc.tobe.support.HttpRequestArgumentResolver;
import core.mvc.tobe.support.HttpResponseArgumentResolver;
import core.mvc.tobe.support.ModelArgumentResolver;
import core.mvc.tobe.support.PathVariableArgumentResolver;
import core.mvc.tobe.support.RequestParamArgumentResolver;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static org.reflections.ReflectionUtils.getAllMethods;
import static org.reflections.util.ReflectionUtilsPredicates.withAnnotation;

public class AnnotationHandlerMapping implements HandlerMapping {
    private static final Logger logger = LoggerFactory.getLogger(AnnotationHandlerMapping.class);

    private final Object[] basePackage;
    private BeanFactory beanFactory;
    private static final List<ArgumentResolver> argumentResolvers = asList(
            new HttpRequestArgumentResolver(),
            new HttpResponseArgumentResolver(),
            new RequestParamArgumentResolver(),
            new PathVariableArgumentResolver(),
            new ModelArgumentResolver()
    );

    private static final ParameterNameDiscoverer nameDiscoverer = new LocalVariableTableParameterNameDiscoverer();


    private final Map<HandlerKey, HandlerExecution> handlerExecutions = Maps.newHashMap();

    public AnnotationHandlerMapping(Object... basePackage) {
        this.basePackage = basePackage;
    }

    public void initialize() {
        logger.info("## Initialized Annotation Handler Mapping");

        Reflections reflections = new Reflections(this.basePackage);
        Set<Class<?>> controllerTypes = reflections.getTypesAnnotatedWith(Controller.class);

        this.beanFactory = new BeanFactory(controllerTypes);
        try {
            beanFactory.initialize();
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
            logger.error("Bean Factory Initialize Failed. cause: {}", e.getMessage());
            throw new BeanFactoryInitFailedException(e.getCause());
        }

        handlerExecutions.putAll(getHandlerExecutions(controllerTypes));
    }

    private Map<HandlerKey, HandlerExecution> getHandlerExecutions(Set<Class<?>> controllerTypes) {
        return controllerTypes.stream()
                .map(this::createHandlerExecution)
                .flatMap(List::stream)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private List<Map.Entry<HandlerKey, HandlerExecution>> createHandlerExecution(Class<?> controllerType) {
        @SuppressWarnings("unchecked")
        Set<Method> methods = getAllMethods(controllerType, withAnnotation(RequestMapping.class));

        return methods.stream()
                .flatMap(method-> {
                    List<Map.Entry<HandlerKey, HandlerExecution>> list = createHandlerEntry(controllerType, method);
                    return list.stream();
                }).collect(Collectors.toList());
    }

    private List<Map.Entry<HandlerKey, HandlerExecution>> createHandlerEntry(Class<?> controllerType, Method method) {
        Controller cAnno = controllerType.getDeclaredAnnotation(Controller.class);
        RequestMapping rmAnno = method.getAnnotation(RequestMapping.class);
        HandlerExecution handlerExecution = new HandlerExecution(nameDiscoverer,
                argumentResolvers,
                beanFactory.getBean(controllerType),
                method);

        RequestMethod[] requestMethods = rmAnno.method();
        if (requestMethods.length == 0) {
            requestMethods = RequestMethod.values();
        }

        return Arrays.stream(requestMethods).map(m -> {
            HandlerKey handlerKey = new HandlerKey(cAnno.path() + rmAnno.value(), m);
            return Map.entry(handlerKey, handlerExecution);
        }).collect(Collectors.toList());
    }

    @Override
    public HandlerExecution getHandler(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        RequestMethod rm = RequestMethod.valueOf(request.getMethod().toUpperCase());

        return handlerExecutions.get(new HandlerKey(requestUri, rm));
    }
}
