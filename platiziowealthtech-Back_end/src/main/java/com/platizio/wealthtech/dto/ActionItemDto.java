package com.platizio.wealthtech.dto;

public class ActionItemDto {
    private String id;
    private String category;
    private String priority;
    private String investor;
    private String desc;
    private String age;

    public ActionItemDto() {}

    public ActionItemDto(String id, String category, String priority, String investor, String desc, String age) {
        this.id = id;
        this.category = category;
        this.priority = priority;
        this.investor = investor;
        this.desc = desc;
        this.age = age;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getInvestor() { return investor; }
    public void setInvestor(String investor) { this.investor = investor; }
    public String getDesc() { return desc; }
    public void setDesc(String desc) { this.desc = desc; }
    public String getAge() { return age; }
    public void setAge(String age) { this.age = age; }
}
