package example.context;

public @interface Transactional {
    Propagation propagation() default Propagation.REQUIRED;
}
