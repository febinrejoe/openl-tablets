/*
 * This class has been generated. Do not change it. 
*/

package org.openl.generated.beans;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.openl.generated.beans.InsurableVehicle;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.openl.generated.beans.InsurableDriver;
import org.apache.commons.lang.ArrayUtils;
import java.util.Vector;

public class VehiclePremiumCalculator{
  protected org.openl.generated.beans.InsurableVehicle vehicle;

  protected java.util.Vector discountsForVehicle;

  protected java.util.Vector discountsForDriver;

  protected org.openl.generated.beans.InsurableDriver designatedDriver;



public VehiclePremiumCalculator() {
    super();
}

public VehiclePremiumCalculator(InsurableVehicle vehicle, Vector discountsForVehicle, Vector discountsForDriver, InsurableDriver designatedDriver) {
    super();
    this.vehicle = vehicle;
    this.discountsForVehicle = discountsForVehicle;
    this.discountsForDriver = discountsForDriver;
    this.designatedDriver = designatedDriver;
}

public int hashCode() {
    HashCodeBuilder builder = new HashCodeBuilder();
    builder.append(vehicle);
    builder.append(discountsForVehicle);
    builder.append(discountsForDriver);
    builder.append(designatedDriver);
    return builder.toHashCode();
}

public boolean equals(Object obj) {
    EqualsBuilder builder = new EqualsBuilder();
    if (!(obj instanceof VehiclePremiumCalculator)) {;
        return false;
    }
    VehiclePremiumCalculator another = (VehiclePremiumCalculator)obj;
    builder.append(another.vehicle,vehicle);
    builder.append(another.discountsForVehicle,discountsForVehicle);
    builder.append(another.discountsForDriver,discountsForDriver);
    builder.append(another.designatedDriver,designatedDriver);
    return builder.isEquals();
}

public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("VehiclePremiumCalculator {");
    builder.append(" vehicle=");
    builder.append(vehicle);
    builder.append(" discountsForVehicle=");
    builder.append(discountsForVehicle);
    builder.append(" discountsForDriver=");
    builder.append(discountsForDriver);
    builder.append(" designatedDriver=");
    builder.append(designatedDriver);
    builder.append(" }");
    return builder.toString();
}
  public org.openl.generated.beans.InsurableVehicle getVehicle() {
   return vehicle;
}
  public void setVehicle(org.openl.generated.beans.InsurableVehicle vehicle) {
   this.vehicle = vehicle;
}
  public java.util.Vector getDiscountsForVehicle() {
   return discountsForVehicle;
}
  public void setDiscountsForVehicle(java.util.Vector discountsForVehicle) {
   this.discountsForVehicle = discountsForVehicle;
}
  public java.util.Vector getDiscountsForDriver() {
   return discountsForDriver;
}
  public void setDiscountsForDriver(java.util.Vector discountsForDriver) {
   this.discountsForDriver = discountsForDriver;
}
  public org.openl.generated.beans.InsurableDriver getDesignatedDriver() {
   return designatedDriver;
}
  public void setDesignatedDriver(org.openl.generated.beans.InsurableDriver designatedDriver) {
   this.designatedDriver = designatedDriver;
}

}