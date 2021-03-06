package water.api;

import water.H2O;
import java.util.ArrayList;

class TypeaheadHandler extends Handler {
  @SuppressWarnings("unused") // called through reflection by RequestServer
  public Schema files(int version, TypeaheadV2 t) {
    ArrayList<String> matches = H2O.getPM().calcTypeaheadMatches(t.src, t.limit);
    t.matches = matches.toArray(new String[matches.size()]);
    return t;
  }
}
