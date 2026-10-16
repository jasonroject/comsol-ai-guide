import com.comsol.model.*;
import com.comsol.model.util.*;
import java.io.*;
import java.util.*;

/** COMSOL 6.3: independent linear heat-conduction verification case. */
public class SmokeConduction {
  static String dir;

  static void box(Model m, String tag, String xmin, String xmax) {
    m.component("c").selection().create(tag, "Box");
    m.component("c").selection(tag).set("entitydim", 1);
    m.component("c").selection(tag).set("condition", "inside");
    m.component("c").selection(tag).set("xmin", xmin);
    m.component("c").selection(tag).set("xmax", xmax);
    m.component("c").selection(tag).set("ymin", "-eps0");
    m.component("c").selection(tag).set("ymax", "H+eps0");
    if (m.component("c").selection(tag).entities().length != 1)
      throw new IllegalStateException("Expected exactly one boundary: " + tag);
  }

  static double scalar(Model m, String tag, String type, String expr, String unit) {
    for (String old : m.result().numerical().tags())
      if (old.equals(tag)) m.result().numerical().remove(tag);
    m.result().numerical().create(tag, type);
    m.result().numerical(tag).selection().all();
    m.result().numerical(tag).set("data", "guideData");
    m.result().numerical(tag).set("expr", new String[]{expr});
    m.result().numerical(tag).set("unit", new String[]{unit});
    double v = m.result().numerical(tag).getReal()[0][0];
    if (!Double.isFinite(v)) throw new IllegalStateException("Nonfinite " + tag);
    return v;
  }

  static void check(Model m, String phase) throws Exception {
    double mean = scalar(m,"guideMean","AvSurface","T","K");
    double error = scalar(m,"guideError","MaxSurface",
        "abs(T-(Tc+(Th-Tc)*x/L))","K");
    double flux = scalar(m,"guideFlux","AvSurface","-k0*d(T,x)","W/m^2");
    boolean pass = Math.abs(mean-298.15)<1e-5 && error<1e-5
        && Math.abs(flux+1000)<1e-3;
    try(PrintWriter out = new PrintWriter(new FileWriter(new File(dir,phase+".json")))) {
      out.printf(Locale.US,
          "{\"phase\":\"%s\",\"dataset\":\"guideData\",\"mean_T_K\":%.15g,\"max_analytic_error_K\":%.15g,\"mean_qx_W_m2\":%.15g,\"passed\":%s}%n",
          phase,mean,error,flux,Boolean.toString(pass));
    }
    if (!pass) throw new IllegalStateException("Analytic verification failed: " + phase);
    System.out.println("COMSOL_GUIDE_PHASE_OK="+phase);
  }

  static Model build() {
    Model m = ModelUtil.create("GuideSmoke");
    m.modelPath(dir); m.label("Analytic conduction automation smoke test");
    m.param().set("L","0.1[m]"); m.param().set("H","0.02[m]");
    m.param().set("eps0","1e-8[m]");
    m.param().set("Tc","293.15[K]");m.param().set("Th","303.15[K]");
    m.param().set("k0","10[W/(m*K)]");
    m.component().create("c",true);
    m.component("c").geom().create("g",2);
    m.component("c").geom("g").lengthUnit("m");
    m.component("c").geom("g").create("r","Rectangle");
    m.component("c").geom("g").feature("r").set("size",new String[]{"L","H"});
    m.component("c").geom("g").run();
    box(m,"left","-eps0","eps0");
    box(m,"right","L-eps0","L+eps0");
    m.component("c").material().create("mat","Common");
    m.component("c").material("mat").selection().all();
    m.component("c").material("mat").propertyGroup("def").set("thermalconductivity","k0");
    m.component("c").material("mat").propertyGroup("def").set("density","1000[kg/m^3]");
    m.component("c").material("mat").propertyGroup("def").set("heatcapacity","1000[J/(kg*K)]");
    m.component("c").physics().create("ht","HeatTransfer","g");
    m.component("c").physics("ht").create("cold","TemperatureBoundary",1);
    m.component("c").physics("ht").feature("cold").selection().named("left");
    m.component("c").physics("ht").feature("cold").set("T0","Tc");
    m.component("c").physics("ht").create("hot","TemperatureBoundary",1);
    m.component("c").physics("ht").feature("hot").selection().named("right");
    m.component("c").physics("ht").feature("hot").set("T0","Th");
    m.component("c").mesh().create("mesh");
    m.component("c").mesh("mesh").autoMeshSize(5);
    m.component("c").mesh("mesh").run();
    m.study().create("std");m.study("std").create("stat","Stationary");
    m.study("std").run();
    m.result().dataset().create("guideData","Solution");
    m.result().dataset("guideData").set("solution","sol1");
    return m;
  }

  public static void main(String[] args) throws Exception {
    dir = System.getenv("COMSOL_GUIDE_OUT");
    if (dir==null) throw new IllegalArgumentException("Set COMSOL_GUIDE_OUT");
    boolean verify = "verify".equals(System.getenv("COMSOL_GUIDE_MODE"));
    Model m = null;
    try {
      if (verify) {
        m=ModelUtil.load("GuideReopened",new File(dir,"smoke.mph").getPath());
        check(m,"reopened_cached");
        m.sol("sol1").clearSolutionData();
        m.study("std").run();
        check(m,"reopened_recomputed");
      } else {
        m=build();check(m,"first");
        m.save(new File(dir,"smoke.mph").getPath());
      }
      System.out.println("COMSOL_GUIDE_OK="+(verify?"verify":"build"));
    } finally {
      if (m!=null) ModelUtil.remove(m.tag());
    }
  }
}
