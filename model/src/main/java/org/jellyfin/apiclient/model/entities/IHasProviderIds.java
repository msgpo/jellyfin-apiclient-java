package org.jellyfin.apiclient.model.entities;

/** 
 Since BaseItem and DTOBaseItem both have ProviderIds, this interface helps avoid code repition by using extension methods
*/
public interface IHasProviderIds
{
	/** 
	 Gets or sets the provider ids.
	 
	 <value>The provider ids.</value>
	*/
	java.util.HashMap<String, String> getProviderIds();
	void setProviderIds(java.util.HashMap<String, String> value);
}