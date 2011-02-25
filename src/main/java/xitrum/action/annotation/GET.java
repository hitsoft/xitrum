package xitrum.action.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface GET {
  String value();
  boolean first() default false;
  boolean last()  default false;
}