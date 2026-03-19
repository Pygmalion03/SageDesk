import lombok.Data;
@Data
class A { private String name; }
class B { String x(A a) { return a.getName(); } }
