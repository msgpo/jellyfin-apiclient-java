package org.jellyfin.apiclient.model.dlna;

public class HttpHeaderInfo
{
//C# TO JAVA CONVERTER TODO TASK: Java annotations will not correspond to .NET attributes:
//ORIGINAL LINE: [XmlAttribute("name")] public string Name {get;set;}
	private String Name;
	public final String getName()
	{
		return Name;
	}
	public final void setName(String value)
	{
		Name = value;
	}

//C# TO JAVA CONVERTER TODO TASK: Java annotations will not correspond to .NET attributes:
//ORIGINAL LINE: [XmlAttribute("value")] public string Value {get;set;}
	private String Value;
	public final String getValue()
	{
		return Value;
	}
	public final void setValue(String value)
	{
		Value = value;
	}

//C# TO JAVA CONVERTER TODO TASK: Java annotations will not correspond to .NET attributes:
//ORIGINAL LINE: [XmlAttribute("match")] public HeaderMatchType Match {get;set;}
	private HeaderMatchType Match = HeaderMatchType.values()[0];
	public final HeaderMatchType getMatch()
	{
		return Match;
	}
	public final void setMatch(HeaderMatchType value)
	{
		Match = value;
	}
}