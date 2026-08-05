package example.aop;

@Aspect
@Component
public class AuditAspect {

    @Around("@annotation(Audited)")
    public Object audit(ProceedingJoinPoint joinPoint) throws Throwable {
        return joinPoint.proceed();
    }

    @AfterThrowing("within(example.aop..*)")
    public void recordFailure(Exception exception) {
    }
}
